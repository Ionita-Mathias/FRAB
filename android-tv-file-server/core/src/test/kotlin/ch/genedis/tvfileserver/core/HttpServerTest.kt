package ch.genedis.tvfileserver.core

import ch.genedis.tvfileserver.core.http.HttpBody
import ch.genedis.tvfileserver.core.http.HttpHeaderNames
import ch.genedis.tvfileserver.core.http.HttpRequest
import ch.genedis.tvfileserver.core.http.HttpResponse
import ch.genedis.tvfileserver.core.http.HttpServer
import ch.genedis.tvfileserver.core.http.HttpServerConfig
import ch.genedis.tvfileserver.core.http.HttpStatus
import ch.genedis.tvfileserver.core.http.RangeParser
import ch.genedis.tvfileserver.core.http.Router
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HttpServerTest {

    private lateinit var scope: CoroutineScope
    private lateinit var server: HttpServer
    private var port = 0
    private val requestCount = AtomicInteger(0)

    private val payload = ByteArray(5_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val router = Router()
            .get("/hello") { HttpResponse.text("hello world") }
            .get("/count") {
                HttpResponse.text(requestCount.incrementAndGet().toString())
            }
            .post("/echo") { request ->
                HttpResponse(HttpStatus.OK, body = HttpBody.Bytes(request.body.readBytes()))
            }
            .get("/bytes") { request -> rangeResponse(request) }
            .get("/stream") {
                HttpResponse(
                    HttpStatus.OK,
                    body = HttpBody.Streaming(null) { out ->
                        for (i in 0 until 10) out.write("chunk-$i;".toByteArray())
                    },
                )
            }
            .post("/discard") { HttpResponse.text("ok") }
            .route("/onlyput", setOf("PUT")) { HttpResponse.text("put") }

        server = HttpServer(HttpServerConfig(port = 0, maxConnections = 8), router.asHandler())
        server.start(scope)
        port = server.boundPort
        assertTrue("server should have bound a port", port > 0)
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    private fun rangeResponse(request: HttpRequest): HttpResponse {
        val ranges = RangeParser.parse(request.header(HttpHeaderNames.RANGE), payload.size.toLong())
        if (ranges != null && ranges.isEmpty()) {
            return HttpResponse.status(HttpStatus.RANGE_NOT_SATISFIABLE)
                .header(HttpHeaderNames.CONTENT_RANGE, RangeParser.unsatisfiedContentRange(payload.size.toLong()))
        }
        val range = ranges?.firstOrNull()
        return if (range == null) {
            HttpResponse(HttpStatus.OK, body = HttpBody.Bytes(payload))
                .header(HttpHeaderNames.ACCEPT_RANGES, "bytes")
        } else {
            val slice = payload.copyOfRange(range.start.toInt(), range.endInclusive.toInt() + 1)
            HttpResponse(HttpStatus.PARTIAL_CONTENT, body = HttpBody.Bytes(slice))
                .header(HttpHeaderNames.CONTENT_RANGE, RangeParser.contentRange(range, payload.size.toLong()))
                .header(HttpHeaderNames.ACCEPT_RANGES, "bytes")
        }
    }

    @Test(timeout = 15_000)
    fun `serves a simple GET`() {
        val response = httpRequest(port, "GET", "/hello")
        assertEquals(200, response.status)
        assertEquals("hello world", response.text)
        assertEquals("11", response.header("Content-Length"))
        assertNotNull(response.header("Date"))
        assertEquals("TvFileServer", response.header("Server"))
    }

    @Test(timeout = 15_000)
    fun `HEAD returns headers without a body`() {
        val response = httpRequest(port, "HEAD", "/hello")
        assertEquals(200, response.status)
        assertEquals("11", response.header("Content-Length"))
        assertEquals(0, response.body.size)
    }

    @Test(timeout = 15_000)
    fun `unknown paths return 404`() {
        assertEquals(404, httpRequest(port, "GET", "/nope").status)
    }

    @Test(timeout = 15_000)
    fun `wrong method returns 405 with Allow`() {
        val response = httpRequest(port, "GET", "/onlyput")
        assertEquals(405, response.status)
        assertTrue(response.header("Allow")!!.contains("PUT"))
    }

    @Test(timeout = 15_000)
    fun `reuses one socket for several keep-alive requests`() {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val results = mutableListOf<String>()
            for (i in 0 until 3) {
                writeRequest(socket.getOutputStream(), "GET", "/count", emptyMap(), null, keepAlive = true)
                val response = readResponse(input, "GET")
                assertEquals(200, response.status)
                assertEquals("keep-alive", response.header("Connection"))
                results.add(response.text)
            }
            assertEquals(listOf("1", "2", "3"), results)
        }
    }

    @Test(timeout = 15_000)
    fun `decodes a chunked request body`() {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 10_000
            val out = socket.getOutputStream()
            val head = "POST /echo HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n"
            out.write(head.toByteArray())
            out.write("5\r\nhello\r\n".toByteArray())
            out.write("7;ext=1\r\n, world\r\n".toByteArray())
            out.write("0\r\n\r\n".toByteArray())
            out.flush()
            val response = readResponse(BufferedInputStream(socket.getInputStream()), "POST")
            assertEquals(200, response.status)
            assertEquals("hello, world", response.text)
        }
    }

    @Test(timeout = 15_000)
    fun `honours Expect 100-continue`() {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 10_000
            val out = socket.getOutputStream()
            val body = "continued".toByteArray()
            val head = "POST /echo HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n" +
                "Expect: 100-continue\r\nContent-Length: ${body.size}\r\n\r\n"
            out.write(head.toByteArray())
            out.flush()

            val input = BufferedInputStream(socket.getInputStream())
            val interim = readAsciiLine(input)
            assertTrue("expected a 100 Continue, got $interim", interim!!.contains("100"))
            readAsciiLine(input) // blank line closing the interim response

            out.write(body)
            out.flush()
            val response = readResponse(input, "POST")
            assertEquals(200, response.status)
            assertEquals("continued", response.text)
        }
    }

    @Test(timeout = 15_000)
    fun `serves a byte range`() {
        val response = httpRequest(port, "GET", "/bytes", mapOf("Range" to "bytes=100-199"))
        assertEquals(206, response.status)
        assertEquals(100, response.body.size)
        assertEquals("bytes 100-199/5000", response.header("Content-Range"))
        assertEquals(payload[100], response.body[0])
    }

    @Test(timeout = 15_000)
    fun `rejects an unsatisfiable range`() {
        val response = httpRequest(port, "GET", "/bytes", mapOf("Range" to "bytes=99999-"))
        assertEquals(416, response.status)
        assertEquals("bytes */5000", response.header("Content-Range"))
    }

    @Test(timeout = 15_000)
    fun `streams a length-less body with chunked encoding`() {
        val response = httpRequest(port, "GET", "/stream")
        assertEquals(200, response.status)
        assertEquals("chunked", response.header("Transfer-Encoding"))
        assertEquals("chunk-0;chunk-1;chunk-2;chunk-3;chunk-4;chunk-5;chunk-6;chunk-7;chunk-8;chunk-9;", response.text)
    }

    @Test(timeout = 15_000)
    fun `drains an unread request body and keeps the connection`() {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val body = ByteArray(4096) { 'x'.code.toByte() }
            writeRequest(socket.getOutputStream(), "POST", "/discard", emptyMap(), body, keepAlive = true)
            assertEquals(200, readResponse(input, "POST").status)

            writeRequest(socket.getOutputStream(), "GET", "/hello", emptyMap(), null, keepAlive = true)
            val second = readResponse(input, "GET")
            assertEquals(200, second.status)
            assertEquals("hello world", second.text)
        }
    }

    @Test(timeout = 15_000)
    fun `rejects an over-long request line`() {
        val response = httpRequest(port, "GET", "/" + "a".repeat(9_000))
        assertEquals(414, response.status)
    }

    @Test(timeout = 15_000)
    fun `rejects obsolete header folding`() {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 10_000
            val out = socket.getOutputStream()
            out.write("GET /hello HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Folded: a\r\n b\r\n\r\n".toByteArray())
            out.flush()
            val response = readResponse(BufferedInputStream(socket.getInputStream()), "GET")
            assertEquals(400, response.status)
        }
    }

    @Test(timeout = 15_000)
    fun `serves concurrent requests`() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val ok = AtomicInteger(0)
        try {
            for (i in 0 until threads) {
                pool.submit {
                    try {
                        if (httpRequest(port, "GET", "/hello").status == 200) ok.incrementAndGet()
                    } finally {
                        ready.countDown()
                    }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            assertEquals(threads, ok.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test(timeout = 15_000)
    fun `stop closes the listener`() {
        server.stop()
        var refused = false
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { it.getInputStream().read() }
        } catch (error: Exception) {
            refused = true
        }
        assertTrue("connections must fail once the server is stopped", refused)
    }
}
