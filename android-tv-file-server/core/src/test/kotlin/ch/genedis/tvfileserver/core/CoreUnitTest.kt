package ch.genedis.tvfileserver.core

import ch.genedis.tvfileserver.core.auth.AuthPolicy
import ch.genedis.tvfileserver.core.auth.Credentials
import ch.genedis.tvfileserver.core.auth.HttpAuthenticator
import ch.genedis.tvfileserver.core.auth.LoginThrottler
import ch.genedis.tvfileserver.core.auth.SessionStore
import ch.genedis.tvfileserver.core.config.CoreConfig
import ch.genedis.tvfileserver.core.http.HttpHeaders
import ch.genedis.tvfileserver.core.http.HttpRequest
import ch.genedis.tvfileserver.core.transfer.TransferDirection
import ch.genedis.tvfileserver.core.transfer.TransferProtocol
import ch.genedis.tvfileserver.core.transfer.TransferRegistry
import ch.genedis.tvfileserver.core.web.JsonWriter
import ch.genedis.tvfileserver.core.webdav.DavLockManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64

class JsonWriterTest {

    @Test
    fun `builds a nested object`() {
        val json = JsonWriter.obj {
            name("name").value("film.mkv")
            name("size").value(1234L)
            name("dir").value(false)
            name("parent").value(null as String?)
            name("tags").beginArray()
            value("a")
            value("b")
            endArray()
            name("meta").beginObject()
            name("depth").value(2)
            endObject()
        }
        assertEquals(
            """{"name":"film.mkv","size":1234,"dir":false,"parent":null,"tags":["a","b"],"meta":{"depth":2}}""",
            json,
        )
    }

    @Test
    fun `builds an array of objects`() {
        val json = JsonWriter.arr {
            beginObject()
            name("id").value(1)
            endObject()
            beginObject()
            name("id").value(2)
            endObject()
        }
        assertEquals("""[{"id":1},{"id":2}]""", json)
    }

    @Test
    fun `escapes what would break a parser or a script tag`() {
        assertEquals("""a\"b""", JsonWriter.escape("a\"b"))
        assertEquals("""a\\b""", JsonWriter.escape("a\\b"))
        assertEquals("""line\nbreak""", JsonWriter.escape("line\nbreak"))
        assertEquals("""tab\there""", JsonWriter.escape("tab\there"))
        assertEquals("""\u0001""", JsonWriter.escape("\u0001"))
        assertEquals("""\u2028""", JsonWriter.escape("\u2028"))
        assertEquals("""\u2029""", JsonWriter.escape("\u2029"))
        assertEquals("é★", JsonWriter.escape("é★"))
    }

    @Test
    fun `escapes a hostile file name inside a document`() {
        val json = JsonWriter.obj { name("n").value("</script><img src=x onerror=alert(1)>") }
        assertTrue(json, json.contains("\\u003"))
        assertFalse("the raw closing tag must not survive", json.contains("</script>"))
    }

    @Test
    fun `raw values pass through`() {
        assertEquals("""{"nested":{"a":1}}""", JsonWriter.obj { name("nested").rawValue("""{"a":1}""") })
    }
}

class AuthTest {

    private fun request(headers: Map<String, String> = emptyMap(), remote: String = "10.0.0.5"): HttpRequest =
        HttpRequest(
            method = "GET",
            rawTarget = "/api/list",
            path = "/api/list",
            rawPath = "/api/list",
            queryParams = emptyMap(),
            headers = HttpHeaders(headers),
            body = ByteArrayInputStream(ByteArray(0)),
            protocol = "HTTP/1.1",
            remoteAddress = remote,
            localAddress = "10.0.0.1",
            localPort = 8080,
        )

    private fun authenticator(
        enabled: Boolean = true,
        anonymousRead: Boolean = false,
    ) = HttpAuthenticator(
        AuthPolicy(enabled, Credentials("tv", "s3cret"), anonymousRead),
    )

    private fun basic(user: String, password: String) =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    @Test
    fun `accepts correct Basic credentials`() {
        val result = authenticator().authenticate(request(mapOf("Authorization" to basic("tv", "s3cret"))))
        assertTrue(result.authenticated)
        assertEquals("tv", result.username)
        assertFalse(result.readOnly)
    }

    @Test
    fun `rejects wrong credentials`() {
        assertFalse(authenticator().authenticate(request(mapOf("Authorization" to basic("tv", "nope")))).authenticated)
        assertFalse(authenticator().authenticate(request(mapOf("Authorization" to basic("x", "s3cret")))).authenticated)
        assertFalse(authenticator().authenticate(request()).authenticated)
    }

    @Test
    fun `ignores malformed Basic data instead of crashing`() {
        assertFalse(authenticator().authenticate(request(mapOf("Authorization" to "Basic !!!"))).authenticated)
        assertFalse(authenticator().authenticate(request(mapOf("Authorization" to "Basic"))).authenticated)
        assertFalse(authenticator().authenticate(request(mapOf("Authorization" to "Weird abc"))).authenticated)
    }

    @Test
    fun `a disabled policy lets everyone in`() {
        val result = authenticator(enabled = false).authenticate(request())
        assertTrue(result.authenticated)
        assertFalse(result.readOnly)
    }

    @Test
    fun `anonymous read grants a read-only identity`() {
        val result = authenticator(anonymousRead = true).authenticate(request())
        assertTrue(result.authenticated)
        assertTrue(result.readOnly)
    }

    @Test
    fun `a session cookie authenticates`() {
        val auth = authenticator()
        val token = auth.sessions.create("tv")
        val result = auth.authenticate(request(mapOf("Cookie" to "tvfs_session=$token; other=1")))
        assertTrue(result.authenticated)
        assertEquals("tv", result.username)
    }

    @Test
    fun `a bearer token and the auto-login token authenticate`() {
        val auth = authenticator()
        val token = auth.sessions.create("tv")
        assertTrue(auth.authenticate(request(mapOf("Authorization" to "Bearer $token"))).authenticated)
        assertTrue(auth.authenticate(request(mapOf("Authorization" to "Bearer ${auth.sessions.autoLoginToken}"))).authenticated)
        assertFalse(auth.authenticate(request(mapOf("Authorization" to "Bearer nonsense"))).authenticated)
    }

    @Test
    fun `login throttles a guesser`() {
        val auth = authenticator()
        repeat(10) { assertNull(auth.login(request(), "tv", "wrong")) }
        assertTrue(auth.isThrottled("10.0.0.5"))
        // Even the right password is refused while the window is open.
        assertNull(auth.login(request(), "tv", "s3cret"))
        // A different client is unaffected.
        assertNotNull(auth.login(request(remote = "10.0.0.6"), "tv", "s3cret"))
    }

    @Test
    fun `the challenge names the realm`() {
        val challenge = authenticator().challenge()
        assertEquals(401, challenge.status.code)
        assertTrue(challenge.headers["WWW-Authenticate"]!!.contains("realm=\"TV File Server\""))
    }

    @Test
    fun `ftp login honours the policy`() {
        assertTrue(authenticator().checkFtp("tv", "s3cret", "10.0.0.5"))
        assertFalse(authenticator().checkFtp("tv", "bad", "10.0.0.5"))
        assertTrue(authenticator(enabled = false).checkFtp("anyone", "", "10.0.0.5"))
        assertTrue(authenticator(anonymousRead = true).checkFtp("anonymous", "e@mail", "10.0.0.5"))
        assertFalse(authenticator().checkFtp("anonymous", "e@mail", "10.0.0.5"))
    }

    @Test
    fun `constant time comparison still compares correctly`() {
        assertTrue(HttpAuthenticator.constantTimeEquals("abc", "abc"))
        assertFalse(HttpAuthenticator.constantTimeEquals("abc", "abd"))
        assertFalse(HttpAuthenticator.constantTimeEquals("abc", "abcd"))
        assertTrue(HttpAuthenticator.constantTimeEquals("", ""))
    }
}

class SessionStoreTest {

    @Test
    fun `validates and invalidates`() {
        val store = SessionStore()
        val token = store.create("tv")
        assertEquals("tv", store.validate(token))
        store.invalidate(token)
        assertNull(store.validate(token))
        assertNull(store.validate(null))
        assertNull(store.validate("bogus"))
    }

    @Test
    fun `expires an old session`() {
        val store = SessionStore(ttlMillis = 1)
        val token = store.create("tv")
        Thread.sleep(20)
        assertNull(store.validate(token))
    }

    @Test
    fun `invalidateAll rotates the auto-login token`() {
        val store = SessionStore()
        val first = store.autoLoginToken
        val token = store.create("tv")
        store.invalidateAll()
        assertNull(store.validate(token))
        assertNotEquals(first, store.autoLoginToken)
    }

    @Test
    fun `tokens are unique and url-safe`() {
        val store = SessionStore()
        val tokens = (0 until 20).map { store.create("tv") }
        assertEquals(20, tokens.toSet().size)
        assertTrue(tokens.all { it.matches(Regex("[A-Za-z0-9_-]+")) })
    }
}

class LoginThrottlerTest {

    @Test
    fun `blocks after the configured number of failures`() {
        val throttler = LoginThrottler(maxFailures = 3, windowMillis = 10_000)
        assertFalse(throttler.isBlocked("a"))
        repeat(3) { throttler.recordFailure("a") }
        assertTrue(throttler.isBlocked("a"))
        assertTrue(throttler.retryAfterSeconds("a") > 0)
        assertFalse(throttler.isBlocked("b"))
    }

    @Test
    fun `a success clears the record`() {
        val throttler = LoginThrottler(maxFailures = 2, windowMillis = 10_000)
        throttler.recordFailure("a")
        throttler.recordSuccess("a")
        throttler.recordFailure("a")
        assertFalse(throttler.isBlocked("a"))
    }

    @Test
    fun `the window expires`() {
        val throttler = LoginThrottler(maxFailures = 1, windowMillis = 20)
        throttler.recordFailure("a")
        assertTrue(throttler.isBlocked("a"))
        Thread.sleep(40)
        assertFalse(throttler.isBlocked("a"))
    }
}

class TransferRegistryTest {

    @Test
    fun `tracks an active transfer and moves it to history`() {
        val registry = TransferRegistry()
        val handle = registry.begin(
            "film.mkv", "/data/film.mkv", TransferDirection.DOWNLOAD, TransferProtocol.HTTP, "10.0.0.5", 1000,
        )
        assertEquals(1, registry.active.value.size)
        assertEquals(1, registry.totals.value.activeCount)

        handle.advance(400)
        handle.complete()

        assertTrue(registry.active.value.isEmpty())
        assertEquals(0, registry.totals.value.activeCount)
        assertEquals(1, registry.recent.value.size)
        assertEquals(400L, registry.recent.value[0].transferred)
        assertEquals(400L, registry.totals.value.bytesDownloaded)
    }

    @Test
    fun `records a failure with its message`() {
        val registry = TransferRegistry()
        val handle = registry.begin("a", "/a", TransferDirection.UPLOAD, TransferProtocol.FTP, "c", -1)
        handle.advance(10)
        handle.fail(java.io.IOException("disk full"))
        assertEquals("disk full", registry.recent.value[0].error)
        assertEquals(10L, registry.totals.value.bytesUploaded)
    }

    @Test
    fun `close is idempotent`() {
        val registry = TransferRegistry()
        val handle = registry.begin("a", "/a", TransferDirection.UPLOAD, TransferProtocol.HTTP, "c", 1)
        handle.close()
        handle.close()
        handle.complete()
        assertEquals(1, registry.recent.value.size)
    }

    @Test
    fun `history is capped`() {
        val registry = TransferRegistry(historyLimit = 3)
        repeat(5) { index ->
            registry.begin("f$index", "/f$index", TransferDirection.UPLOAD, TransferProtocol.HTTP, "c", 1).complete()
        }
        assertEquals(3, registry.recent.value.size)
        assertEquals("f4", registry.recent.value[0].name)
    }

    @Test
    fun `progress ratio is null when the size is unknown`() {
        val registry = TransferRegistry()
        val handle = registry.begin("a", "/a", TransferDirection.UPLOAD, TransferProtocol.HTTP, "c", -1)
        handle.advance(50)
        assertNull(registry.snapshot()[0].progress)
        handle.complete()

        val known = registry.begin("b", "/b", TransferDirection.UPLOAD, TransferProtocol.HTTP, "c", 200)
        known.advance(50)
        assertEquals(0.25f, registry.snapshot()[0].progress!!, 0.001f)
        known.complete()
    }

    @Test
    fun `reset clears history and counters`() {
        val registry = TransferRegistry()
        registry.begin("a", "/a", TransferDirection.UPLOAD, TransferProtocol.HTTP, "c", 1).also {
            it.advance(5)
            it.complete()
        }
        registry.reset()
        assertTrue(registry.recent.value.isEmpty())
        assertEquals(0L, registry.totals.value.bytesUploaded)
    }
}

class CoreConfigTest {

    @Test
    fun `clamps nonsense into workable values`() {
        val validated = CoreConfig(
            httpPort = 0,
            ftpPort = 99999,
            webdavMount = "dav/",
            maxHttpConnections = 1,
            maxFtpSessions = 500,
            passivePortStart = 50,
            passivePortEnd = 10,
            bufferSize = 1,
            username = "  ",
        ).validated()

        assertEquals(8080, validated.httpPort)
        assertEquals(2121, validated.ftpPort)
        assertEquals("/dav", validated.webdavMount)
        assertEquals(4, validated.maxHttpConnections)
        assertEquals(32, validated.maxFtpSessions)
        assertEquals(1024, validated.passivePortStart)
        assertTrue(validated.passivePortEnd >= validated.passivePortStart)
        assertEquals(8192, validated.bufferSize)
        assertEquals("tv", validated.username)
    }

    @Test
    fun `keeps a sane configuration untouched`() {
        val config = CoreConfig(httpPort = 9000, ftpPort = 2222, webdavMount = "/webdav")
        assertEquals(config, config.validated())
    }

    @Test
    fun `moves FTP off a colliding port`() {
        assertEquals(2121, CoreConfig(httpPort = 8080, ftpPort = 8080).validated().ftpPort)
    }

    @Test
    fun `derives the auth policy`() {
        val policy = CoreConfig(username = "u", password = "p", authEnabled = true, serverName = "X").authPolicy()
        assertTrue(policy.enabled)
        assertEquals("u", policy.credentials.username)
        assertEquals("X", policy.realm)
    }
}

class DavLockManagerTest {

    private val path = ch.genedis.tvfileserver.core.vfs.VPath.of("/data/file.txt")

    @Test
    fun `locks then unlocks`() {
        val manager = DavLockManager()
        val lock = manager.lock(path, 0, "owner", exclusive = true, timeoutSeconds = 600)
        assertTrue(lock.token.startsWith("opaquelocktoken:"))
        assertNotNull(manager.find(path))
        assertTrue(manager.isLockedForOthers(path, emptyList()))
        assertFalse(manager.isLockedForOthers(path, listOf(lock.token)))
        assertFalse(manager.isLockedForOthers(path, listOf("<${lock.token}>")))

        assertTrue(manager.unlock(lock.token))
        assertNull(manager.find(path))
        assertFalse(manager.unlock(lock.token))
    }

    @Test
    fun `a depth-infinity lock covers descendants`() {
        val manager = DavLockManager()
        val parent = ch.genedis.tvfileserver.core.vfs.VPath.of("/data")
        manager.lock(parent, Int.MAX_VALUE, "owner", exclusive = true, timeoutSeconds = null)
        assertNotNull(manager.find(path))

        val manager0 = DavLockManager()
        manager0.lock(parent, 0, "owner", exclusive = true, timeoutSeconds = null)
        assertNull(manager0.find(path))
    }

    @Test
    fun `expired locks disappear`() {
        val manager = DavLockManager()
        val lock = manager.lock(path, 0, "o", exclusive = true, timeoutSeconds = 1)
        lock.expiresAt = System.currentTimeMillis() - 1
        assertNull(manager.find(path))
    }

    @Test
    fun `refresh extends a lock`() {
        val manager = DavLockManager()
        val lock = manager.lock(path, 0, "o", exclusive = true, timeoutSeconds = 1)
        val before = lock.expiresAt
        val refreshed = manager.refresh(lock.token, 600)
        assertNotNull(refreshed)
        assertTrue(refreshed!!.expiresAt > before)
        assertNull(manager.refresh("unknown", 600))
    }

    @Test
    fun `parses timeout headers`() {
        assertEquals(600L, DavLockManager.parseTimeout("Second-600"))
        assertEquals(600L, DavLockManager.parseTimeout("Infinite, Second-600"))
        assertEquals(DavLockManager.MAX_TIMEOUT_SECONDS, DavLockManager.parseTimeout("Infinite"))
        assertNull(DavLockManager.parseTimeout(null))
        assertNull(DavLockManager.parseTimeout("nonsense"))
    }

    @Test
    fun `parses lock tokens out of an If header`() {
        assertEquals(
            listOf("opaquelocktoken:abc"),
            DavLockManager.parseIfTokens("(<opaquelocktoken:abc>)"),
        )
        assertEquals(
            listOf("opaquelocktoken:a", "opaquelocktoken:b"),
            DavLockManager.parseIfTokens("(<opaquelocktoken:a>) (<opaquelocktoken:b>)"),
        )
        assertEquals(
            listOf("opaquelocktoken:a"),
            DavLockManager.parseIfTokens("</dav/f.txt> (<opaquelocktoken:a> [\"etag\"])"),
        )
        assertTrue(DavLockManager.parseIfTokens(null).isEmpty())
        assertTrue(DavLockManager.parseIfTokens("garbage").isEmpty())
    }
}
