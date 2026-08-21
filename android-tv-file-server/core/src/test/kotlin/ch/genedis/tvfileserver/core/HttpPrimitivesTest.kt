package ch.genedis.tvfileserver.core

import ch.genedis.tvfileserver.core.http.ByteRange
import ch.genedis.tvfileserver.core.http.ChunkedInputStream
import ch.genedis.tvfileserver.core.http.ChunkedOutputStream
import ch.genedis.tvfileserver.core.http.CountingInputStream
import ch.genedis.tvfileserver.core.http.CountingOutputStream
import ch.genedis.tvfileserver.core.http.HttpDates
import ch.genedis.tvfileserver.core.http.HttpHeaders
import ch.genedis.tvfileserver.core.http.HttpRequest
import ch.genedis.tvfileserver.core.http.HttpStatus
import ch.genedis.tvfileserver.core.http.LimitedInputStream
import ch.genedis.tvfileserver.core.http.MimeTypes
import ch.genedis.tvfileserver.core.http.RangeParser
import ch.genedis.tvfileserver.core.http.UrlCodec
import ch.genedis.tvfileserver.core.util.copyStream
import ch.genedis.tvfileserver.core.util.readAtMost
import ch.genedis.tvfileserver.core.util.skipFully
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpHeadersTest {

    @Test
    fun `lookup ignores case but preserves the original spelling`() {
        val headers = HttpHeaders()
        headers["Content-Type"] = "text/plain"
        assertEquals("text/plain", headers["content-type"])
        assertEquals("text/plain", headers["CONTENT-TYPE"])
        assertEquals(listOf("Content-Type" to "text/plain"), headers.toList())
    }

    @Test
    fun `add keeps every value and set replaces them`() {
        val headers = HttpHeaders()
        headers.add("Set-Cookie", "a=1")
        headers.add("set-cookie", "b=2")
        assertEquals(listOf("a=1", "b=2"), headers.all("Set-Cookie"))
        assertEquals("a=1", headers["Set-Cookie"])
        assertEquals(2, headers.size)

        headers["Set-Cookie"] = "c=3"
        assertEquals(listOf("c=3"), headers.all("Set-Cookie"))
    }

    @Test
    fun `setIfAbsent does not overwrite`() {
        val headers = HttpHeaders(mapOf("Server" to "mine"))
        headers.setIfAbsent("Server", "other")
        headers.setIfAbsent("Date", "now")
        assertEquals("mine", headers["Server"])
        assertEquals("now", headers["Date"])
    }

    @Test
    fun `numeric accessors fall back`() {
        val headers = HttpHeaders(mapOf("Content-Length" to "42", "Bad" to "abc"))
        assertEquals(42, headers.getInt("Content-Length"))
        assertEquals(42L, headers.getLong("content-length"))
        assertEquals(-1, headers.getInt("Bad"))
        assertEquals(7L, headers.getLong("Missing", 7L))
    }

    @Test
    fun `copy is independent`() {
        val headers = HttpHeaders(mapOf("A" to "1"))
        val copy = headers.copy()
        copy["A"] = "2"
        assertEquals("1", headers["A"])
        assertEquals("2", copy["A"])
    }

    @Test
    fun `remove and contains work`() {
        val headers = HttpHeaders(mapOf("A" to "1"))
        assertTrue("a" in headers)
        headers.remove("A")
        assertFalse("A" in headers)
        assertTrue(headers.isEmpty)
    }
}

class UrlCodecTest {

    @Test
    fun `decodes percent escapes as UTF-8`() {
        assertEquals("a b", UrlCodec.decode("a%20b"))
        assertEquals("résumé", UrlCodec.decode("r%C3%A9sum%C3%A9"))
        assertEquals("★", UrlCodec.decode("%E2%98%85"))
        assertEquals("a/b", UrlCodec.decode("a%2Fb"))
    }

    @Test
    fun `passes a malformed escape through unchanged`() {
        assertEquals("100%", UrlCodec.decode("100%"))
        assertEquals("50%zz", UrlCodec.decode("50%zz"))
        assertEquals("a%2", UrlCodec.decode("a%2"))
    }

    @Test
    fun `plus is a space only in query mode`() {
        assertEquals("a+b", UrlCodec.decode("a+b"))
        assertEquals("a b", UrlCodec.decode("a+b", plusAsSpace = true))
    }

    @Test
    fun `encodePath keeps separators and encodePart does not`() {
        assertEquals("/a%20b/c", UrlCodec.encodePath("/a b/c"))
        assertEquals("%2Fa%20b%2Fc", UrlCodec.encodeComponent("/a b/c"))
        assertEquals("r%C3%A9sum%C3%A9.txt", UrlCodec.encodeComponent("résumé.txt"))
        assertEquals("a-b_c.d~e", UrlCodec.encodePath("a-b_c.d~e"))
    }

    @Test
    fun `parses a query into a multimap`() {
        val query = UrlCodec.parseQuery("a=1&a=2&b=hello+world&flag&c=%2Fx")
        assertEquals(listOf("1", "2"), query["a"])
        assertEquals(listOf("hello world"), query["b"])
        assertEquals(listOf(""), query["flag"])
        assertEquals(listOf("/x"), query["c"])
        assertTrue(UrlCodec.parseQuery(null).isEmpty())
        assertTrue(UrlCodec.parseQuery("").isEmpty())
    }
}

class HttpDatesTest {

    // Sun, 06 Nov 1994 08:49:37 GMT
    private val reference = 784111777000L

    @Test
    fun `formats RFC 1123`() {
        assertEquals("Sun, 06 Nov 1994 08:49:37 GMT", HttpDates.format(reference))
    }

    @Test
    fun `parses all three accepted formats`() {
        assertEquals(reference, HttpDates.parse("Sun, 06 Nov 1994 08:49:37 GMT"))
        assertEquals(reference, HttpDates.parse("Sunday, 06-Nov-94 08:49:37 GMT"))
        assertEquals(reference, HttpDates.parse("Sun Nov  6 08:49:37 1994"))
    }

    @Test
    fun `returns null for junk`() {
        assertNull(HttpDates.parse(null))
        assertNull(HttpDates.parse(""))
        assertNull(HttpDates.parse("yesterday"))
    }

    @Test
    fun `formats ISO for WebDAV`() {
        assertEquals("1994-11-06T08:49:37Z", HttpDates.formatIso(reference))
    }

    @Test
    fun `round-trips`() {
        assertEquals(reference, HttpDates.parse(HttpDates.format(reference)))
    }
}

class MimeTypesTest {

    @Test
    fun `maps the formats a TV box stores`() {
        assertEquals("video/x-matroska", MimeTypes.forFileName("film.mkv"))
        assertEquals("video/mp4", MimeTypes.forFileName("clip.MP4"))
        assertEquals("audio/flac", MimeTypes.forFileName("track.flac"))
        assertEquals("image/jpeg", MimeTypes.forFileName("photo.JPEG"))
        assertEquals("application/pdf", MimeTypes.forFileName("manual.pdf"))
        assertEquals("text/html", MimeTypes.forFileName("index.html"))
        assertEquals("application/vnd.android.package-archive", MimeTypes.forFileName("app.apk"))
    }

    @Test
    fun `falls back to octet-stream`() {
        assertEquals(MimeTypes.OCTET_STREAM, MimeTypes.forFileName("mystery.qqq"))
        assertEquals(MimeTypes.OCTET_STREAM, MimeTypes.forFileName("noextension"))
        assertEquals(MimeTypes.OCTET_STREAM, MimeTypes.forFileName("trailing."))
    }

    @Test
    fun `recognises inline-displayable text`() {
        assertTrue(MimeTypes.isText("text/plain; charset=utf-8"))
        assertTrue(MimeTypes.isText("application/json"))
        assertTrue(MimeTypes.isText("application/gpx+xml"))
        assertFalse(MimeTypes.isText("video/mp4"))
    }

    @Test
    fun `knows which payloads are already compressed`() {
        assertTrue(MimeTypes.isPrecompressed("film.mkv"))
        assertTrue(MimeTypes.isPrecompressed("photo.jpg"))
        assertTrue(MimeTypes.isPrecompressed("archive.zip"))
        assertFalse(MimeTypes.isPrecompressed("notes.txt"))
        assertFalse(MimeTypes.isPrecompressed("image.bmp"))
    }
}

class RangeParserTest {

    @Test
    fun `returns null when there is nothing to honour`() {
        assertNull(RangeParser.parse(null, 100))
        assertNull(RangeParser.parse("items=0-10", 100))
        assertNull(RangeParser.parse("bytes=", 100))
        assertNull(RangeParser.parse("bytes=abc", 100))
    }

    @Test
    fun `parses the common forms`() {
        assertEquals(listOf(ByteRange(0, 99)), RangeParser.parse("bytes=0-", 100))
        assertEquals(listOf(ByteRange(10, 20)), RangeParser.parse("bytes=10-20", 100))
        assertEquals(listOf(ByteRange(90, 99)), RangeParser.parse("bytes=-10", 100))
        assertEquals(listOf(ByteRange(0, 99)), RangeParser.parse("bytes=-500", 100))
    }

    @Test
    fun `clamps an end past the entity`() {
        assertEquals(listOf(ByteRange(50, 99)), RangeParser.parse("bytes=50-4000", 100))
    }

    @Test
    fun `reports unsatisfiable ranges as an empty list`() {
        assertEquals(emptyList<ByteRange>(), RangeParser.parse("bytes=100-", 100))
        assertEquals(emptyList<ByteRange>(), RangeParser.parse("bytes=500-600", 100))
        assertEquals(emptyList<ByteRange>(), RangeParser.parse("bytes=0-10", 0))
    }

    @Test
    fun `parses several ranges`() {
        assertEquals(
            listOf(ByteRange(0, 9), ByteRange(20, 29)),
            RangeParser.parse("bytes=0-9, 20-29", 100),
        )
    }

    @Test
    fun `builds content range headers`() {
        assertEquals("bytes 10-20/100", RangeParser.contentRange(ByteRange(10, 20), 100))
        assertEquals("bytes */100", RangeParser.unsatisfiedContentRange(100))
    }

    @Test
    fun `length is inclusive`() {
        assertEquals(11L, ByteRange(10, 20).length)
        assertEquals(1L, ByteRange(0, 0).length)
    }
}

class StreamsTest {

    @Test
    fun `LimitedInputStream stops at the limit and leaves the rest`() {
        val source = ByteArrayInputStream("abcdefghij".toByteArray())
        val limited = LimitedInputStream(source, 4)
        assertEquals("abcd", String(limited.readBytes()))
        assertEquals(-1, limited.read())
        limited.close()
        assertEquals("efghij", String(source.readBytes()))
    }

    @Test
    fun `LimitedInputStream reports what is left`() {
        val limited = LimitedInputStream(ByteArrayInputStream(ByteArray(10)), 6)
        assertEquals(6L, limited.remaining)
        limited.read(ByteArray(4))
        assertEquals(2L, limited.remaining)
        assertFalse(limited.markSupported())
    }

    @Test
    fun `ChunkedInputStream decodes chunks extensions and trailers`() {
        val encoded = "5\r\nhello\r\n7;charset=x\r\n, world\r\n0\r\nX-Trailer: 1\r\n\r\n"
        val decoded = ChunkedInputStream(ByteArrayInputStream(encoded.toByteArray())).readBytes()
        assertEquals("hello, world", String(decoded))
    }

    @Test
    fun `ChunkedInputStream handles an empty body`() {
        val decoded = ChunkedInputStream(ByteArrayInputStream("0\r\n\r\n".toByteArray())).readBytes()
        assertEquals(0, decoded.size)
    }

    @Test
    fun `ChunkedOutputStream round-trips`() {
        val raw = ByteArrayOutputStream()
        val chunked = ChunkedOutputStream(raw)
        chunked.write("hello".toByteArray())
        chunked.write(", world".toByteArray())
        chunked.finish()
        chunked.finish() // idempotent

        val encoded = raw.toByteArray()
        assertTrue(String(encoded).endsWith("0\r\n\r\n"))
        assertEquals("hello, world", String(ChunkedInputStream(ByteArrayInputStream(encoded)).readBytes()))
    }

    @Test
    fun `counting streams count every overload`() {
        var read = 0L
        val counting = CountingInputStream(ByteArrayInputStream(ByteArray(10))) { read += it }
        counting.read()
        counting.read(ByteArray(4))
        assertEquals(5L, read)

        var written = 0L
        val out = CountingOutputStream(ByteArrayOutputStream()) { written += it }
        out.write(1)
        out.write(ByteArray(9))
        assertEquals(10L, written)
    }
}

class HttpRequestHelpersTest {

    @Test
    fun `splits a target into path and query`() {
        assertEquals("/a" to "x=1", HttpRequest.splitTarget("/a?x=1"))
        assertEquals("/a" to null, HttpRequest.splitTarget("/a"))
        assertEquals("/a" to "x=1", HttpRequest.splitTarget("/a?x=1#frag"))
        assertEquals("/" to "", HttpRequest.splitTarget("/?"))
    }

    @Test
    fun `normalises a decoded path`() {
        assertEquals("/a/b", HttpRequest.normalizePath("/a//b"))
        assertEquals("/a", HttpRequest.normalizePath("/a/b/.."))
        assertEquals("/", HttpRequest.normalizePath("/.."))
        assertEquals("/etc/passwd", HttpRequest.normalizePath("/../../etc/passwd"))
        assertEquals("/a/", HttpRequest.normalizePath("/a/"))
    }
}

class HttpStatusTest {

    @Test
    fun `known codes resolve to their canonical reason`() {
        assertEquals(HttpStatus.NOT_FOUND, HttpStatus.of(404))
        assertEquals("207 Multi-Status", HttpStatus.MULTI_STATUS.toString())
        assertEquals("Insufficient Storage", HttpStatus.of(507).reason)
    }

    @Test
    fun `unknown codes get a generic reason`() {
        assertEquals("Client Error", HttpStatus.of(499).reason)
        assertEquals(499, HttpStatus.of(499).code)
    }

    @Test
    fun `classifies codes`() {
        assertTrue(HttpStatus.OK.isSuccess)
        assertTrue(HttpStatus.FOUND.isRedirect)
        assertTrue(HttpStatus.LOCKED.isError)
    }
}

class IoTest {

    @Test
    fun `copyStream honours the limit and reports progress`() {
        val source = ByteArrayInputStream(ByteArray(1000) { 7 })
        val sink = ByteArrayOutputStream()
        var reported = 0L
        val copied = copyStream(
            source, sink, ByteArray(64), limit = 300, onProgress = { reported += it },
        )
        assertEquals(300L, copied)
        assertEquals(300L, reported)
        assertEquals(300, sink.size())
    }

    @Test
    fun `copyStream stops when cancelled`() {
        val sink = ByteArrayOutputStream()
        var active = true
        val copied = copyStream(
            ByteArrayInputStream(ByteArray(10_000)),
            sink,
            ByteArray(100),
            isActive = {
                val was = active
                active = false
                was
            },
        )
        assertEquals(100L, copied)
    }

    @Test
    fun `readAtMost rejects an oversized payload`() {
        val data = ByteArray(1000)
        assertArrayEquals(data, ByteArrayInputStream(data).readAtMost(1000))
        var failed = false
        try {
            ByteArrayInputStream(data).readAtMost(999)
        } catch (error: java.io.IOException) {
            failed = true
        }
        assertTrue("must refuse to buffer more than the limit", failed)
    }

    @Test
    fun `skipFully skips across a stubborn stream`() {
        val stream = ByteArrayInputStream(ByteArray(100))
        assertEquals(40L, stream.skipFully(40))
        assertEquals(60, stream.available())
    }
}
