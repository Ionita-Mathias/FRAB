package ch.genedis.tvfileserver.core

import ch.genedis.tvfileserver.core.auth.AuthPolicy
import ch.genedis.tvfileserver.core.auth.Credentials
import ch.genedis.tvfileserver.core.auth.HttpAuthenticator
import ch.genedis.tvfileserver.core.config.CoreConfig
import ch.genedis.tvfileserver.core.http.HttpServer
import ch.genedis.tvfileserver.core.http.HttpServerConfig
import ch.genedis.tvfileserver.core.transfer.TransferRegistry
import ch.genedis.tvfileserver.core.vfs.LocalFileSystem
import ch.genedis.tvfileserver.core.web.InMemoryAssetSource
import ch.genedis.tvfileserver.core.web.ServerInfo
import ch.genedis.tvfileserver.core.web.WebInterfaceHandler
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
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipInputStream

class WebInterfaceHandlerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var server: HttpServer
    private lateinit var storage: File
    private lateinit var auth: HttpAuthenticator
    private var port = 0
    private var readOnly = false

    private val basic = "Basic " + Base64.getEncoder().encodeToString("tv:secret".toByteArray())
    private val csrf = mapOf("X-Requested-With" to "TvFileServer")

    @Before
    fun setUp() {
        storage = temporaryFolder.newFolder("storage")
        File(storage, "Movies").mkdirs()
        File(storage, "Movies/film.mkv").writeText("video-payload")
        File(storage, "readme.txt").writeText("hello world")

        auth = HttpAuthenticator(
            AuthPolicy(enabled = true, credentials = Credentials("tv", "secret"), allowAnonymousRead = false),
        )
        startServer()
    }

    private fun startServer() {
        val vfs = LocalFileSystem(
            roots = testFileSystem(storage).roots(),
            readOnly = readOnly,
        )
        val config = CoreConfig(username = "tv", password = "secret", readOnly = readOnly)
        val handler = WebInterfaceHandler(
            vfs = vfs,
            auth = auth,
            assets = InMemoryAssetSource(
                mapOf(
                    "index.html" to "<html>ui</html>".toByteArray(),
                    "app.js" to "console.log(1)".toByteArray(),
                ),
            ),
            transfers = TransferRegistry(),
            configProvider = { config },
            infoProvider = {
                ServerInfo(
                    serverName = "TV File Server",
                    appVersion = "1.0.0",
                    httpPort = port,
                    ftpPort = 2121,
                    ftpEnabled = true,
                    webdavEnabled = true,
                    webdavMount = "/dav",
                    addresses = listOf("127.0.0.1"),
                    readOnly = readOnly,
                    authEnabled = true,
                    deviceName = "Test TV",
                )
            },
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server = HttpServer(HttpServerConfig(port = 0), handler.asHandler())
        server.start(scope)
        port = server.boundPort
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    private fun restartReadOnly() {
        server.stop()
        scope.cancel()
        readOnly = true
        startServer()
    }

    // ------------------------------------------------------------------ auth

    @Test(timeout = 15_000)
    fun `health needs no credentials`() {
        val response = httpRequest(port, "GET", "/health")
        assertEquals(200, response.status)
        assertEquals("ok", response.text)
    }

    @Test(timeout = 15_000)
    fun `session reports an anonymous caller`() {
        val response = httpRequest(port, "GET", "/api/session")
        assertEquals(200, response.status)
        assertTrue(response.text.contains("\"authenticated\":false"))
        assertTrue(response.text.contains("\"authEnabled\":true"))
    }

    @Test(timeout = 15_000)
    fun `a browser gets JSON not a Basic challenge`() {
        val response = httpRequest(port, "GET", "/api/list", mapOf("Accept" to "text/html,application/xhtml+xml"))
        assertEquals(401, response.status)
        assertTrue(response.text.contains("unauthorized"))
        assertEquals(null, response.header("WWW-Authenticate"))
    }

    @Test(timeout = 15_000)
    fun `a script gets a Basic challenge`() {
        val response = httpRequest(port, "GET", "/api/list", mapOf("Accept" to "*/*"))
        assertEquals(401, response.status)
        assertTrue(response.header("WWW-Authenticate")!!.startsWith("Basic realm="))
    }

    @Test(timeout = 15_000)
    fun `login issues a session cookie that authenticates later calls`() {
        val login = httpRequest(
            port, "POST", "/api/login",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            "username=tv&password=secret".toByteArray(),
        )
        assertEquals(200, login.status)
        val cookie = login.header("Set-Cookie")!!.substringBefore(';')
        assertTrue(cookie.startsWith("tvfs_session="))

        val listed = httpRequest(port, "GET", "/api/list?path=/data", mapOf("Cookie" to cookie))
        assertEquals(200, listed.status)
        assertTrue(listed.text.contains("readme.txt"))
    }

    @Test(timeout = 15_000)
    fun `login rejects a wrong password`() {
        val response = httpRequest(
            port, "POST", "/api/login",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            "username=tv&password=wrong".toByteArray(),
        )
        assertEquals(401, response.status)
        assertEquals(null, response.header("Set-Cookie"))
    }

    @Test(timeout = 15_000)
    fun `a cookie write without the CSRF header is refused`() {
        val login = httpRequest(
            port, "POST", "/api/login",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            "username=tv&password=secret".toByteArray(),
        )
        val cookie = login.header("Set-Cookie")!!.substringBefore(';')

        val blocked = httpRequest(
            port, "POST", "/api/mkdir",
            mapOf("Cookie" to cookie, "Content-Type" to "application/x-www-form-urlencoded"),
            "path=/data&name=nope".toByteArray(),
        )
        assertEquals(403, blocked.status)
        assertTrue(blocked.text.contains("csrf"))
        assertFalse(File(storage, "nope").exists())

        val allowed = httpRequest(
            port, "POST", "/api/mkdir",
            mapOf("Cookie" to cookie, "Content-Type" to "application/x-www-form-urlencoded") + csrf,
            "path=/data&name=yes".toByteArray(),
        )
        assertEquals(200, allowed.status)
        assertTrue(File(storage, "yes").isDirectory)
    }

    @Test(timeout = 15_000)
    fun `Basic callers are exempt from the CSRF header`() {
        val response = httpRequest(
            port, "POST", "/api/mkdir",
            mapOf("Authorization" to basic, "Content-Type" to "application/x-www-form-urlencoded"),
            "path=/data&name=viabasic".toByteArray(),
        )
        assertEquals(200, response.status)
        assertTrue(File(storage, "viabasic").isDirectory)
    }

    // ------------------------------------------------------------------ browsing

    @Test(timeout = 15_000)
    fun `lists the virtual root and a folder`() {
        val roots = httpRequest(port, "GET", "/api/list?path=/", mapOf("Authorization" to basic))
        assertEquals(200, roots.status)
        assertTrue(roots.text.contains("\"path\":\"/data\""))

        val folder = httpRequest(port, "GET", "/api/list?path=/data", mapOf("Authorization" to basic))
        assertEquals(200, folder.status)
        assertTrue(folder.text.contains("\"name\":\"Movies\""))
        assertTrue(folder.text.contains("\"name\":\"readme.txt\""))
        assertTrue(folder.text.contains("\"parent\":\"/\""))
    }

    @Test(timeout = 15_000)
    fun `roots reports free space`() {
        val response = httpRequest(port, "GET", "/api/roots", mapOf("Authorization" to basic))
        assertEquals(200, response.status)
        assertTrue(response.text.contains("\"id\":\"data\""))
        assertTrue(response.text.contains("\"free\":"))
    }

    @Test(timeout = 15_000)
    fun `refuses a traversal attempt`() {
        val response = httpRequest(
            port, "GET", "/api/list?path=%2Fdata%2F..%2F..%2Fetc",
            mapOf("Authorization" to basic),
        )
        assertEquals(404, response.status)
    }

    // ------------------------------------------------------------------ download

    @Test(timeout = 15_000)
    fun `downloads a file with a content disposition`() {
        val response = httpRequest(port, "GET", "/api/download?path=/data/readme.txt", mapOf("Authorization" to basic))
        assertEquals(200, response.status)
        assertEquals("hello world", response.text)
        assertTrue(response.header("Content-Disposition")!!.startsWith("attachment;"))
        assertEquals("bytes", response.header("Accept-Ranges"))
    }

    @Test(timeout = 15_000)
    fun `serves a range so a player can seek`() {
        val response = httpRequest(
            port, "GET", "/api/raw?path=/data/readme.txt",
            mapOf("Authorization" to basic, "Range" to "bytes=6-10"),
        )
        assertEquals(206, response.status)
        assertEquals("world", response.text)
        assertEquals("bytes 6-10/11", response.header("Content-Range"))
        assertTrue(response.header("Content-Disposition")!!.startsWith("inline;"))
    }

    @Test(timeout = 15_000)
    fun `answers 304 for a matching etag`() {
        val first = httpRequest(port, "GET", "/api/download?path=/data/readme.txt", mapOf("Authorization" to basic))
        val etag = first.header("ETag")!!
        val second = httpRequest(
            port, "GET", "/api/download?path=/data/readme.txt",
            mapOf("Authorization" to basic, "If-None-Match" to etag),
        )
        assertEquals(304, second.status)
        assertEquals(0, second.body.size)
    }

    @Test(timeout = 15_000)
    fun `zips a folder`() {
        val response = httpRequest(port, "GET", "/api/zip?path=/data/Movies", mapOf("Authorization" to basic))
        assertEquals(200, response.status)
        assertEquals("application/zip", response.header("Content-Type"))

        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(response.body)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
                zip.closeEntry()
            }
        }
        assertTrue("expected Movies/film.mkv in $names", names.any { it.endsWith("film.mkv") })
    }

    @Test(timeout = 15_000)
    fun `zips an explicit selection`() {
        val response = httpRequest(
            port, "GET",
            "/api/zip?path=%2Fdata&paths=%5B%22%2Fdata%2Freadme.txt%22%5D&name=picked",
            mapOf("Authorization" to basic),
        )
        assertEquals(200, response.status)
        assertTrue(response.header("Content-Disposition")!!.contains("picked.zip"))

        ZipInputStream(ByteArrayInputStream(response.body)).use { zip ->
            val entry = zip.nextEntry!!
            assertEquals("readme.txt", entry.name)
            assertEquals("hello world", String(zip.readBytes()))
        }
    }

    // ------------------------------------------------------------------ upload

    @Test(timeout = 15_000)
    fun `uploads files including a nested relative path`() {
        val multipart = MultipartBuilder()
            .file("file", "one.txt", "first".toByteArray())
            .file("file", "season 1/ep01.txt", "nested".toByteArray())
            .build()

        val response = httpRequest(
            port, "POST", "/api/upload?path=/data",
            mapOf("Authorization" to basic, "Content-Type" to MultipartBuilder().contentType),
            multipart,
        )
        assertEquals(200, response.status)
        assertTrue(response.text.contains("/data/one.txt"))
        assertEquals("first", File(storage, "one.txt").readText())
        assertEquals("nested", File(storage, "season 1/ep01.txt").readText())
        assertFalse("the .part file must be renamed away", File(storage, "one.txt.part").exists())
    }

    @Test(timeout = 15_000)
    fun `a second upload of the same name is de-duplicated`() {
        for (round in 0 until 2) {
            val body = MultipartBuilder().file("file", "dup.txt", "round$round".toByteArray()).build()
            val response = httpRequest(
                port, "POST", "/api/upload?path=/data",
                mapOf("Authorization" to basic, "Content-Type" to MultipartBuilder().contentType),
                body,
            )
            assertEquals(200, response.status)
        }
        assertEquals("round0", File(storage, "dup.txt").readText())
        assertEquals("round1", File(storage, "dup (2).txt").readText())
    }

    @Test(timeout = 15_000)
    fun `overwrite replaces the existing file`() {
        val body = MultipartBuilder().file("file", "readme.txt", "replaced".toByteArray()).build()
        val response = httpRequest(
            port, "POST", "/api/upload?path=/data&overwrite=true",
            mapOf("Authorization" to basic, "Content-Type" to MultipartBuilder().contentType),
            body,
        )
        assertEquals(200, response.status)
        assertEquals("replaced", File(storage, "readme.txt").readText())
    }

    @Test(timeout = 15_000)
    fun `upload without multipart is refused`() {
        val response = httpRequest(
            port, "POST", "/api/upload?path=/data",
            mapOf("Authorization" to basic, "Content-Type" to "application/json"),
            "{}".toByteArray(),
        )
        assertEquals(415, response.status)
    }

    // ------------------------------------------------------------------ mutations

    @Test(timeout = 15_000)
    fun `renames a file`() {
        val response = httpRequest(
            port, "POST", "/api/rename",
            mapOf("Authorization" to basic, "Content-Type" to "application/x-www-form-urlencoded"),
            "path=%2Fdata%2Freadme.txt&name=renamed.txt".toByteArray(),
        )
        assertEquals(200, response.status)
        assertTrue(File(storage, "renamed.txt").exists())
        assertFalse(File(storage, "readme.txt").exists())
    }

    @Test(timeout = 15_000)
    fun `moves a selection into a folder`() {
        val response = httpRequest(
            port, "POST", "/api/move",
            mapOf("Authorization" to basic, "Content-Type" to "application/x-www-form-urlencoded"),
            "paths=%5B%22%2Fdata%2Freadme.txt%22%5D&destination=%2Fdata%2FMovies".toByteArray(),
        )
        assertEquals(200, response.status)
        assertTrue(File(storage, "Movies/readme.txt").exists())
        assertFalse(File(storage, "readme.txt").exists())
    }

    @Test(timeout = 15_000)
    fun `copies a selection`() {
        val response = httpRequest(
            port, "POST", "/api/copy",
            mapOf("Authorization" to basic, "Content-Type" to "application/x-www-form-urlencoded"),
            "paths=%5B%22%2Fdata%2Freadme.txt%22%5D&destination=%2Fdata%2FMovies".toByteArray(),
        )
        assertEquals(200, response.status)
        assertTrue(File(storage, "Movies/readme.txt").exists())
        assertTrue(File(storage, "readme.txt").exists())
    }

    @Test(timeout = 15_000)
    fun `deletes a selection but never a storage root`() {
        val deleted = httpRequest(
            port, "POST", "/api/delete",
            mapOf("Authorization" to basic, "Content-Type" to "application/x-www-form-urlencoded"),
            "paths=%5B%22%2Fdata%2Freadme.txt%22%5D".toByteArray(),
        )
        assertEquals(200, deleted.status)
        assertFalse(File(storage, "readme.txt").exists())

        val refused = httpRequest(
            port, "POST", "/api/delete",
            mapOf("Authorization" to basic, "Content-Type" to "application/x-www-form-urlencoded"),
            "paths=%5B%22%2Fdata%22%5D".toByteArray(),
        )
        assertEquals(409, refused.status)
        assertTrue(storage.isDirectory)
    }

    // ------------------------------------------------------------------ read-only

    @Test(timeout = 15_000)
    fun `read-only mode refuses every write`() {
        restartReadOnly()
        val response = httpRequest(
            port, "POST", "/api/mkdir",
            mapOf("Authorization" to basic, "Content-Type" to "application/x-www-form-urlencoded"),
            "path=/data&name=blocked".toByteArray(),
        )
        assertEquals(403, response.status)
        assertFalse(File(storage, "blocked").exists())

        val read = httpRequest(port, "GET", "/api/list?path=/data", mapOf("Authorization" to basic))
        assertEquals(200, read.status)
    }

    // ------------------------------------------------------------------ assets

    @Test(timeout = 15_000)
    fun `serves the SPA entry point`() {
        val response = httpRequest(port, "GET", "/")
        assertEquals(200, response.status)
        assertEquals("<html>ui</html>", response.text)
        assertEquals("no-cache", response.header("Cache-Control"))
    }

    @Test(timeout = 15_000)
    fun `serves cacheable assets and honours if-none-match`() {
        val first = httpRequest(port, "GET", "/assets/app.js")
        assertEquals(200, first.status)
        assertEquals("text/javascript", first.header("Content-Type"))
        assertEquals("public, max-age=86400", first.header("Cache-Control"))

        val second = httpRequest(port, "GET", "/assets/app.js", mapOf("If-None-Match" to first.header("ETag")!!))
        assertEquals(304, second.status)
    }

    @Test(timeout = 15_000)
    fun `an asset traversal never reaches the filesystem`() {
        // The server normalises the target before routing, so "/assets/../../etc/passwd"
        // collapses to "/etc/passwd" and no longer matches the asset route at all. The
        // handler's own ".." check is the second line of defence for non-HTTP callers.
        for (attempt in listOf("/assets/..%2F..%2Fetc%2Fpasswd", "/assets/../../etc/passwd", "/assets/%2e%2e/app.js")) {
            val response = httpRequest(port, "GET", attempt)
            assertTrue("$attempt must not succeed, got ${response.status}", response.status >= 400)
            assertFalse(response.text.contains("root:"))
        }
    }

    @Test(timeout = 15_000)
    fun `the QR auto-login token signs the caller in`() {
        val token = auth.sessions.autoLoginToken
        val response = httpRequest(port, "GET", "/?k=" + java.net.URLEncoder.encode(token, "UTF-8"))
        assertEquals(303, response.status)
        assertEquals("/", response.header("Location"))
        assertTrue(response.header("Set-Cookie")!!.startsWith("tvfs_session="))
    }

    @Test(timeout = 15_000)
    fun `a wrong auto-login token just serves the login page`() {
        val response = httpRequest(port, "GET", "/?k=not-the-token")
        assertEquals(200, response.status)
        assertEquals(null, response.header("Set-Cookie"))
    }

    @Test(timeout = 15_000)
    fun `transfers endpoint reports totals`() {
        val response = httpRequest(port, "GET", "/api/transfers", mapOf("Authorization" to basic))
        assertEquals(200, response.status)
        assertTrue(response.text.contains("\"activeCount\":0"))
    }
}
