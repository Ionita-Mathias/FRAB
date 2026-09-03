package ch.genedis.tvfileserver.core

import ch.genedis.tvfileserver.core.auth.AuthPolicy
import ch.genedis.tvfileserver.core.auth.Credentials
import ch.genedis.tvfileserver.core.auth.HttpAuthenticator
import ch.genedis.tvfileserver.core.config.CoreConfig
import ch.genedis.tvfileserver.core.http.HttpServer
import ch.genedis.tvfileserver.core.http.HttpServerConfig
import ch.genedis.tvfileserver.core.http.Router
import ch.genedis.tvfileserver.core.transfer.TransferRegistry
import ch.genedis.tvfileserver.core.webdav.WebDavHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class WebDavHandlerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var server: HttpServer
    private lateinit var storage: File
    private var port = 0

    private val basic = "Basic " + Base64.getEncoder().encodeToString("tv:secret".toByteArray())
    private val authHeader = mapOf("Authorization" to basic)

    @Before
    fun setUp() {
        storage = temporaryFolder.newFolder("storage")
        File(storage, "Movies").mkdirs()
        File(storage, "Movies/film.mkv").writeText("video-payload")
        File(storage, "readme.txt").writeText("hello world")

        val vfs = testFileSystem(storage)
        val auth = HttpAuthenticator(
            AuthPolicy(enabled = true, credentials = Credentials("tv", "secret"), allowAnonymousRead = false),
        )
        val config = CoreConfig(username = "tv", password = "secret")
        val router = Router().mount("/dav", WebDavHandler(vfs, auth, TransferRegistry()) { config })

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server = HttpServer(HttpServerConfig(port = 0), router.asHandler())
        server.start(scope)
        port = server.boundPort
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    @Test(timeout = 15_000)
    fun `OPTIONS advertises DAV class 1 and 2`() {
        val response = httpRequest(port, "OPTIONS", "/dav/")
        assertEquals(200, response.status)
        assertEquals("1, 2", response.header("DAV"))
        assertEquals("DAV", response.header("MS-Author-Via"))
        val allow = response.header("Allow")!!
        for (method in listOf("PROPFIND", "PROPPATCH", "LOCK", "UNLOCK", "MKCOL", "COPY", "MOVE")) {
            assertTrue("Allow must mention $method", allow.contains(method))
        }
    }

    @Test(timeout = 15_000)
    fun `unauthenticated access is challenged`() {
        val response = httpRequest(port, "PROPFIND", "/dav/", mapOf("Depth" to "0"))
        assertEquals(401, response.status)
        assertTrue(response.header("WWW-Authenticate")!!.contains("Basic"))
    }

    @Test(timeout = 15_000)
    fun `PROPFIND depth 0 describes only the collection`() {
        val response = httpRequest(port, "PROPFIND", "/dav/data", authHeader + mapOf("Depth" to "0"))
        assertEquals(207, response.status)
        assertTrue(response.header("Content-Type")!!.startsWith("application/xml"))
        assertEquals(1, countOccurrences(response.text, "<D:response>"))
        assertTrue(response.text.contains("<D:collection/>"))
        assertTrue(response.text.contains("<D:href>/dav/data/</D:href>"))
    }

    @Test(timeout = 15_000)
    fun `PROPFIND depth 1 lists the children`() {
        val response = httpRequest(port, "PROPFIND", "/dav/data", authHeader + mapOf("Depth" to "1"))
        assertEquals(207, response.status)
        assertEquals(3, countOccurrences(response.text, "<D:response>"))
        assertTrue(response.text.contains("/dav/data/readme.txt"))
        assertTrue(response.text.contains("/dav/data/Movies/"))
        assertTrue(response.text.contains("<D:getcontentlength>11</D:getcontentlength>"))
        assertTrue(response.text.contains("<D:getlastmodified>"))
        assertTrue(response.text.contains("<D:supportedlock>"))
    }

    @Test(timeout = 15_000)
    fun `PROPFIND percent-encodes hrefs`() {
        File(storage, "a file & more.txt").writeText("x")
        val response = httpRequest(port, "PROPFIND", "/dav/data", authHeader + mapOf("Depth" to "1"))
        // "&" is percent-encoded by the path encoder before XML escaping ever sees it.
        assertTrue(response.text, response.text.contains("/dav/data/a%20file%20%26%20more.txt"))
    }

    @Test(timeout = 15_000)
    fun `PROPFIND depth infinity is refused`() {
        val response = httpRequest(port, "PROPFIND", "/dav/data", authHeader + mapOf("Depth" to "infinity"))
        assertEquals(403, response.status)
        assertTrue(response.text.contains("propfind-finite-depth"))
    }

    @Test(timeout = 15_000)
    fun `PROPFIND ignores an external entity`() {
        // A DOCTYPE with a file entity must not leak /etc/passwd into the response.
        val hostile = """<?xml version="1.0"?>
            <!DOCTYPE t [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <D:propfind xmlns:D="DAV:"><D:prop><D:displayname>&x;</D:displayname></D:prop></D:propfind>
        """.trimIndent().toByteArray()

        val response = httpRequest(
            port, "PROPFIND", "/dav/data",
            authHeader + mapOf("Depth" to "0", "Content-Type" to "application/xml"),
            hostile,
        )
        assertTrue(response.status == 207 || response.status == 400)
        assertFalse("the parser must not resolve external entities", response.text.contains("root:"))
    }

    @Test(timeout = 15_000)
    fun `GET returns the file bytes with a range`() {
        val whole = httpRequest(port, "GET", "/dav/data/readme.txt", authHeader)
        assertEquals(200, whole.status)
        assertEquals("hello world", whole.text)
        assertEquals("bytes", whole.header("Accept-Ranges"))

        val part = httpRequest(port, "GET", "/dav/data/readme.txt", authHeader + mapOf("Range" to "bytes=0-4"))
        assertEquals(206, part.status)
        assertEquals("hello", part.text)
    }

    @Test(timeout = 15_000)
    fun `PUT creates then overwrites`() {
        val created = httpRequest(port, "PUT", "/dav/data/new.txt", authHeader, "created".toByteArray())
        assertEquals(201, created.status)
        assertEquals("created", File(storage, "new.txt").readText())

        val updated = httpRequest(port, "PUT", "/dav/data/new.txt", authHeader, "updated".toByteArray())
        assertEquals(204, updated.status)
        assertEquals("updated", File(storage, "new.txt").readText())
    }

    @Test(timeout = 15_000)
    fun `PUT into a missing collection is a conflict`() {
        val response = httpRequest(port, "PUT", "/dav/data/absent/file.txt", authHeader, "x".toByteArray())
        assertEquals(409, response.status)
    }

    @Test(timeout = 15_000)
    fun `MKCOL creates a collection`() {
        assertEquals(201, httpRequest(port, "MKCOL", "/dav/data/Series", authHeader).status)
        assertTrue(File(storage, "Series").isDirectory)
        assertEquals(405, httpRequest(port, "MKCOL", "/dav/data/Series", authHeader).status)
    }

    @Test(timeout = 15_000)
    fun `MOVE relocates and COPY duplicates`() {
        val moved = httpRequest(
            port, "MOVE", "/dav/data/readme.txt",
            authHeader + mapOf("Destination" to "/dav/data/Movies/readme.txt"),
        )
        assertEquals(201, moved.status)
        assertTrue(File(storage, "Movies/readme.txt").exists())
        assertFalse(File(storage, "readme.txt").exists())

        val copied = httpRequest(
            port, "COPY", "/dav/data/Movies/readme.txt",
            authHeader + mapOf("Destination" to "http://127.0.0.1:$port/dav/data/copy.txt"),
        )
        assertEquals(201, copied.status)
        assertEquals("hello world", File(storage, "copy.txt").readText())
    }

    @Test(timeout = 15_000)
    fun `MOVE with Overwrite F onto an existing target fails`() {
        val response = httpRequest(
            port, "MOVE", "/dav/data/readme.txt",
            authHeader + mapOf("Destination" to "/dav/data/Movies/film.mkv", "Overwrite" to "F"),
        )
        assertEquals(412, response.status)
        assertTrue(File(storage, "readme.txt").exists())
    }

    @Test(timeout = 15_000)
    fun `DELETE removes a collection recursively`() {
        assertEquals(204, httpRequest(port, "DELETE", "/dav/data/Movies", authHeader).status)
        assertFalse(File(storage, "Movies").exists())
    }

    @Test(timeout = 15_000)
    fun `LOCK returns a token and UNLOCK releases it`() {
        val lockBody = """<?xml version="1.0" encoding="utf-8"?>
            <D:lockinfo xmlns:D="DAV:">
              <D:lockscope><D:exclusive/></D:lockscope>
              <D:locktype><D:write/></D:locktype>
              <D:owner>tester</D:owner>
            </D:lockinfo>
        """.trimIndent().toByteArray()

        val locked = httpRequest(
            port, "LOCK", "/dav/data/readme.txt",
            authHeader + mapOf("Content-Type" to "application/xml", "Timeout" to "Second-600"),
            lockBody,
        )
        assertEquals(200, locked.status)
        val token = locked.header("Lock-Token")!!.trim().removePrefix("<").removeSuffix(">")
        assertTrue(token.startsWith("opaquelocktoken:"))
        assertTrue(locked.text.contains("<D:activelock>"))
        assertTrue(locked.text.contains("<D:timeout>Second-"))

        // Another client without the token is locked out...
        val blocked = httpRequest(port, "PUT", "/dav/data/readme.txt", authHeader, "nope".toByteArray())
        assertEquals(423, blocked.status)
        assertEquals("hello world", File(storage, "readme.txt").readText())

        // ...but the holder can write.
        val allowed = httpRequest(
            port, "PUT", "/dav/data/readme.txt",
            authHeader + mapOf("If" to "(<$token>)"),
            "written".toByteArray(),
        )
        assertEquals(204, allowed.status)
        assertEquals("written", File(storage, "readme.txt").readText())

        val unlocked = httpRequest(port, "UNLOCK", "/dav/data/readme.txt", authHeader + mapOf("Lock-Token" to "<$token>"))
        assertEquals(204, unlocked.status)
        assertEquals(204, httpRequest(port, "PUT", "/dav/data/readme.txt", authHeader, "free".toByteArray()).status)
    }

    @Test(timeout = 15_000)
    fun `LOCK on an unmapped resource creates a placeholder`() {
        val lockBody = """<?xml version="1.0" encoding="utf-8"?>
            <D:lockinfo xmlns:D="DAV:"><D:lockscope><D:exclusive/></D:lockscope>
            <D:locktype><D:write/></D:locktype><D:owner>finder</D:owner></D:lockinfo>
        """.trimIndent().toByteArray()

        val response = httpRequest(
            port, "LOCK", "/dav/data/reserved.txt",
            authHeader + mapOf("Content-Type" to "application/xml"),
            lockBody,
        )
        assertEquals(201, response.status)
        assertTrue(File(storage, "reserved.txt").exists())
    }

    @Test(timeout = 15_000)
    fun `PROPPATCH acknowledges and applies a timestamp`() {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <D:propertyupdate xmlns:D="DAV:"><D:set><D:prop>
              <D:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</D:getlastmodified>
            </D:prop></D:set></D:propertyupdate>
        """.trimIndent().toByteArray()

        val response = httpRequest(
            port, "PROPPATCH", "/dav/data/readme.txt",
            authHeader + mapOf("Content-Type" to "application/xml"),
            body,
        )
        assertEquals(207, response.status)
        assertTrue(response.text.contains("HTTP/1.1 200 OK"))
        assertEquals(1445412480L, File(storage, "readme.txt").lastModified() / 1000)
    }

    @Test(timeout = 15_000)
    fun `an unknown method is rejected with Allow`() {
        val response = httpRequest(port, "PATCH", "/dav/data/readme.txt", authHeader)
        assertEquals(405, response.status)
        assertTrue(response.header("Allow")!!.contains("PROPFIND"))
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }
}
