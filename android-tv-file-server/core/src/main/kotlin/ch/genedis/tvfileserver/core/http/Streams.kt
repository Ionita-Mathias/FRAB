package ch.genedis.tvfileserver.core.http

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Exposes at most [limit] bytes of [source].
 *
 * Closing this stream does **not** close [source]: HTTP request bodies share the connection
 * socket with the following request on a keep-alive connection.
 */
class LimitedInputStream(
    private val source: InputStream,
    private val limit: Long,
) : InputStream() {

    private var consumed = 0L

    /** Number of bytes of the declared length that have not been read yet. */
    val remaining: Long get() = (limit - consumed).coerceAtLeast(0)

    override fun read(): Int {
        if (consumed >= limit) return -1
        val value = source.read()
        if (value >= 0) consumed++
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (consumed >= limit) return -1
        val want = minOf(len.toLong(), limit - consumed).toInt()
        val read = source.read(b, off, want)
        if (read > 0) consumed += read
        return read
    }

    override fun skip(n: Long): Long {
        val want = minOf(n, limit - consumed)
        if (want <= 0) return 0
        val skipped = source.skip(want)
        consumed += skipped
        return skipped
    }

    override fun available(): Int = minOf(source.available().toLong(), remaining).toInt()

    override fun markSupported(): Boolean = false

    override fun close() {
        // Intentionally does not close the underlying socket stream.
    }
}

/**
 * Decodes an RFC 7230 `Transfer-Encoding: chunked` body.
 *
 * Chunk extensions are ignored, trailer headers are consumed, and the stream stops exactly
 * after the terminating CRLF so the connection can be reused.
 */
class ChunkedInputStream(private val source: InputStream) : InputStream() {

    private var chunkRemaining = 0L
    private var finished = false

    override fun read(): Int {
        if (!ensureChunk()) return -1
        val value = source.read()
        if (value < 0) throw IOException("Truncated chunked body")
        chunkRemaining--
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!ensureChunk()) return -1
        val want = minOf(len.toLong(), chunkRemaining).toInt()
        val read = source.read(b, off, want)
        if (read < 0) throw IOException("Truncated chunked body")
        chunkRemaining -= read
        return read
    }

    override fun markSupported(): Boolean = false

    override fun close() {
        // Does not close the underlying socket stream.
    }

    /** Reads chunk framing until data is available, or returns false at the terminal chunk. */
    private fun ensureChunk(): Boolean {
        if (finished) return false
        if (chunkRemaining > 0) return true

        if (chunkRemaining == 0L && sawFirstChunk) {
            // Consume the CRLF that closes the previous chunk.
            expectCrLf()
        }
        val line = readLine() ?: throw IOException("Truncated chunked body: missing chunk size")
        val sizeText = line.substringBefore(';').trim()
        val size = sizeText.toLongOrNull(16)
            ?: throw IOException("Malformed chunk size: '$sizeText'")
        if (size < 0) throw IOException("Negative chunk size")
        sawFirstChunk = true

        if (size == 0L) {
            // Terminal chunk: swallow the optional trailer section.
            while (true) {
                val trailer = readLine() ?: break
                if (trailer.isEmpty()) break
            }
            finished = true
            return false
        }
        chunkRemaining = size
        return true
    }

    private var sawFirstChunk = false

    private fun expectCrLf() {
        val first = source.read()
        if (first < 0) throw IOException("Truncated chunked body")
        if (first == '\r'.code) {
            val second = source.read()
            if (second != '\n'.code) throw IOException("Malformed chunk terminator")
        } else if (first != '\n'.code) {
            throw IOException("Malformed chunk terminator")
        }
    }

    private fun readLine(): String? {
        val builder = StringBuilder(16)
        while (true) {
            val value = source.read()
            if (value < 0) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) return builder.toString()
            if (value != '\r'.code) builder.append(value.toChar())
            if (builder.length > 4096) throw IOException("Chunk header too long")
        }
    }
}

/**
 * Writes an RFC 7230 chunked body.
 *
 * [finish] emits the terminal zero-length chunk; the sink is left open so the connection can
 * serve the next request.
 */
class ChunkedOutputStream(private val sink: OutputStream) : OutputStream() {

    private var finished = false

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        check(!finished) { "Stream already finished" }
        sink.write(Integer.toHexString(len).toByteArray(Charsets.US_ASCII))
        sink.write(CRLF)
        sink.write(b, off, len)
        sink.write(CRLF)
    }

    override fun flush() = sink.flush()

    /** Emits the terminal chunk. Idempotent. */
    fun finish() {
        if (finished) return
        finished = true
        sink.write(TERMINATOR)
        sink.flush()
    }

    override fun close() {
        finish()
    }

    private companion object {
        val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        val TERMINATOR = "0\r\n\r\n".toByteArray(Charsets.US_ASCII)
    }
}

/** Counts every byte read and reports the increment to [onBytes]. */
class CountingInputStream(
    private val source: InputStream,
    private val onBytes: (Long) -> Unit,
) : InputStream() {

    override fun read(): Int {
        val value = source.read()
        if (value >= 0) onBytes(1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = source.read(b, off, len)
        if (read > 0) onBytes(read.toLong())
        return read
    }

    override fun available(): Int = source.available()

    override fun markSupported(): Boolean = false

    override fun close() = source.close()
}

/** Counts every byte written and reports the increment to [onBytes]. */
class CountingOutputStream(
    private val sink: OutputStream,
    private val onBytes: (Long) -> Unit,
) : OutputStream() {

    override fun write(b: Int) {
        sink.write(b)
        onBytes(1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        sink.write(b, off, len)
        if (len > 0) onBytes(len.toLong())
    }

    override fun flush() = sink.flush()

    override fun close() = sink.close()
}
