package ch.genedis.tvfileserver.core.http

import ch.genedis.tvfileserver.core.util.DEFAULT_BUFFER_BYTES
import ch.genedis.tvfileserver.core.util.readAtMost
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/** One part of a `multipart/form-data` payload. */
class MultipartPart internal constructor(
    val headers: HttpHeaders,
    val name: String?,
    /** The raw `filename` value; for directory uploads it still contains the relative path. */
    val fileName: String?,
    val contentType: String?,
    /** Valid only inside the `forEachPart` callback; reports EOF at the part boundary. */
    val stream: InputStream,
) {

    val isFile: Boolean get() = fileName != null

    /** Reads a small non-file part (a form field) as UTF-8 text. */
    fun readText(limit: Long = 64 * 1024): String = String(stream.readAtMost(limit), Charsets.UTF_8)
}

/**
 * Streaming `multipart/form-data` parser (RFC 7578).
 *
 * The whole point of this class is that a part is never buffered: the callback receives a
 * stream that ends exactly at the boundary, so an upload can be written straight to its
 * destination file with constant memory. The sliding window is sized so a boundary split
 * across two socket reads is still matched.
 */
class MultipartParser(
    private val source: InputStream,
    boundary: String,
    bufferSize: Int = DEFAULT_BUFFER_BYTES,
    private val maxHeaderBytes: Int = 16 * 1024,
) {

    /** The delimiter that precedes every part after the first: CRLF + "--" + boundary. */
    private val delimiter: ByteArray = ("\r\n--$boundary").toByteArray(Charsets.ISO_8859_1)

    private val buffer = ByteArray(maxOf(bufferSize, delimiter.size * 2 + 16))
    private var head = 0
    private var tail = 0
    private var sourceExhausted = false

    /**
     * Iterates over the parts in order.
     *
     * The callback does not have to consume [MultipartPart.stream]; anything left is skipped
     * before the next part is produced.
     */
    fun forEachPart(block: (MultipartPart) -> Unit) {
        if (!skipPreamble()) return
        while (true) {
            when (readDelimiterSuffix()) {
                Suffix.END -> return
                Suffix.EOF -> return
                Suffix.PART -> Unit
            }
            val headers = readPartHeaders()
            val disposition = ContentDisposition.parse(headers[HttpHeaderNames.CONTENT_DISPOSITION])
            val partStream = PartInputStream()
            val part = MultipartPart(
                headers = headers,
                name = disposition.name,
                fileName = disposition.fileName,
                contentType = headers[HttpHeaderNames.CONTENT_TYPE],
                stream = partStream,
            )
            block(part)
            partStream.drain()
        }
    }

    // ------------------------------------------------------------------ framing

    private enum class Suffix { PART, END, EOF }

    /**
     * Consumes everything up to and including the first boundary line.
     *
     * The first boundary has no leading CRLF, so it is matched against `--boundary` directly.
     */
    private fun skipPreamble(): Boolean {
        // Reuse the generic search by pretending a CRLF preceded the body: scan for the
        // delimiter, but also accept the delimiter without its leading CRLF at offset 0.
        val opening = delimiter.copyOfRange(2, delimiter.size)
        fill()
        if (available() >= opening.size && regionMatches(head, opening)) {
            head += opening.size
            return true
        }
        val found = discardUntilDelimiter()
        return found
    }

    /** Reads what follows a boundary: CRLF for another part, or `--` for the epilogue. */
    private fun readDelimiterSuffix(): Suffix {
        ensure(2)
        if (available() < 2) return Suffix.EOF
        val first = buffer[head]
        val second = buffer[head + 1]
        if (first == DASH && second == DASH) {
            head += 2
            return Suffix.END
        }
        if (first == CR && second == LF) {
            head += 2
            return Suffix.PART
        }
        // Tolerate trailing whitespace between the boundary and its CRLF.
        var skipped = 0
        while (available() > 0 && (buffer[head] == SPACE || buffer[head] == TAB)) {
            head++
            skipped++
            ensure(2)
        }
        if (skipped > 0) return readDelimiterSuffix()
        throw IOException("Malformed multipart boundary suffix")
    }

    private fun readPartHeaders(): HttpHeaders {
        val headers = HttpHeaders()
        var consumed = 0
        while (true) {
            val line = readHeaderLine()
            consumed += line.length + 2
            if (consumed > maxHeaderBytes) throw IOException("Multipart part headers too large")
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon <= 0) throw IOException("Malformed multipart header: '$line'")
            headers.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
        }
    }

    private fun readHeaderLine(): String {
        val builder = StringBuilder(64)
        while (true) {
            ensure(1)
            if (available() == 0) throw IOException("Truncated multipart headers")
            val value = buffer[head++].toInt() and 0xFF
            if (value == LF.toInt()) {
                if (builder.isNotEmpty() && builder.last() == '\r') builder.setLength(builder.length - 1)
                // Header names and values are ASCII; a UTF-8 filename is decoded separately.
                return builder.toString()
            }
            builder.append(value.toChar())
            if (builder.length > maxHeaderBytes) throw IOException("Multipart header line too long")
        }
    }

    /**
     * Exposes the current part's bytes, stopping at the delimiter.
     *
     * The window always keeps at least `delimiter.size` bytes in reserve so a delimiter that
     * straddles two refills is still found.
     */
    private inner class PartInputStream : InputStream() {

        private var finished = false

        override fun read(): Int {
            val single = ByteArray(1)
            val read = read(single, 0, 1)
            return if (read <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (finished) return -1
            while (true) {
                val match = indexOfDelimiter()
                if (match >= 0) {
                    val usable = match - head
                    if (usable == 0) {
                        head += delimiter.size
                        finished = true
                        return -1
                    }
                    val take = minOf(usable, len)
                    System.arraycopy(buffer, head, b, off, take)
                    head += take
                    return take
                }
                // No delimiter in the window: everything except the last (delimiter-1) bytes
                // is guaranteed not to contain its start, so it is safe to hand out.
                val safe = available() - (delimiter.size - 1)
                if (safe > 0) {
                    val take = minOf(safe, len)
                    System.arraycopy(buffer, head, b, off, take)
                    head += take
                    return take
                }
                if (sourceExhausted) {
                    throw IOException("Truncated multipart body: closing boundary is missing")
                }
                fill()
            }
        }

        override fun available(): Int = if (finished) 0 else maxOf(0, this@MultipartParser.available() - delimiter.size)

        override fun markSupported(): Boolean = false

        override fun close() {
            // The underlying stream is the connection; only the part ends here.
        }

        /** Consumes whatever the callback did not read. */
        fun drain() {
            if (finished) return
            val scratch = ByteArray(8192)
            while (read(scratch, 0, scratch.size) >= 0) {
                // Discard.
            }
        }
    }

    // ------------------------------------------------------------------ buffer plumbing

    private fun available(): Int = tail - head

    /** Ensures at least [count] bytes are buffered when the source still has data. */
    private fun ensure(count: Int) {
        while (available() < count && !sourceExhausted) {
            if (fill() <= 0) break
        }
    }

    /**
     * Compacts the window and reads one more chunk from the source.
     *
     * @return the number of bytes added; 0 at EOF or when the window is already full.
     */
    private fun fill(): Int {
        if (sourceExhausted) return 0
        if (head > 0) {
            val length = available()
            if (length > 0) System.arraycopy(buffer, head, buffer, 0, length)
            head = 0
            tail = length
        }
        if (tail >= buffer.size) return 0
        val read = source.read(buffer, tail, buffer.size - tail)
        if (read < 0) {
            sourceExhausted = true
            return 0
        }
        tail += read
        return read
    }

    /** @return the absolute buffer index of the delimiter, or -1 when it is not in the window. */
    private fun indexOfDelimiter(): Int {
        val limit = tail - delimiter.size
        var index = head
        while (index <= limit) {
            if (buffer[index] == delimiter[0] && regionMatches(index, delimiter)) return index
            index++
        }
        return -1
    }

    private fun regionMatches(offset: Int, pattern: ByteArray): Boolean {
        if (offset + pattern.size > tail) return false
        for (i in pattern.indices) {
            if (buffer[offset + i] != pattern[i]) return false
        }
        return true
    }

    /** Skips the preamble of a body whose first boundary is not at offset 0. */
    private fun discardUntilDelimiter(): Boolean {
        while (true) {
            val match = indexOfDelimiter()
            if (match >= 0) {
                head = match + delimiter.size
                return true
            }
            if (sourceExhausted) return false
            val keep = delimiter.size - 1
            if (available() > keep) head = tail - keep
            fill()
        }
    }

    /** Parsed `Content-Disposition` of a part. */
    private class ContentDisposition(val name: String?, val fileName: String?) {
        companion object {
            fun parse(header: String?): ContentDisposition {
                if (header == null) return ContentDisposition(null, null)
                val params = parseParameters(header)
                val rawName = params["name"]
                val encodedFile = params["filename*"]
                val plainFile = params["filename"]
                val fileName = when {
                    encodedFile != null -> decodeExtended(encodedFile)
                    plainFile != null -> plainFile
                    else -> null
                }
                return ContentDisposition(rawName, fileName)
            }

            /** Splits `form-data; name="x"; filename="y"`, honouring quotes and escapes. */
            private fun parseParameters(header: String): Map<String, String> {
                val result = HashMap<String, String>(4)
                var index = header.indexOf(';')
                while (index >= 0 && index < header.length) {
                    index++
                    while (index < header.length && header[index] == ' ') index++
                    val eq = header.indexOf('=', index)
                    if (eq < 0) break
                    val key = header.substring(index, eq).trim().lowercase(Locale.ROOT)
                    var cursor = eq + 1
                    val value = StringBuilder()
                    if (cursor < header.length && header[cursor] == '"') {
                        cursor++
                        while (cursor < header.length) {
                            val ch = header[cursor]
                            if (ch == '\\' && cursor + 1 < header.length) {
                                value.append(header[cursor + 1])
                                cursor += 2
                                continue
                            }
                            if (ch == '"') {
                                cursor++
                                break
                            }
                            value.append(ch)
                            cursor++
                        }
                    } else {
                        while (cursor < header.length && header[cursor] != ';') {
                            value.append(header[cursor])
                            cursor++
                        }
                    }
                    if (key.isNotEmpty()) result[key] = value.toString().trim()
                    index = header.indexOf(';', cursor)
                }
                return result
            }

            /**
             * Decodes the RFC 5987 `charset'language'percent-encoded` form.
             *
             * Only UTF-8 is decoded faithfully; the other charset browsers may legally send
             * (ISO-8859-1) is a subset for the characters that survive percent-encoding, so
             * decoding it as UTF-8 is safe in practice and never throws.
             */
            private fun decodeExtended(value: String): String {
                val parts = value.split('\'', limit = 3)
                val encoded = if (parts.size < 3) value else parts[2]
                return UrlCodec.decode(encoded)
            }
        }
    }

    companion object {
        private const val CR: Byte = 13
        private const val LF: Byte = 10
        private const val DASH: Byte = 45
        private const val SPACE: Byte = 32
        private const val TAB: Byte = 9

        /** Extracts the `boundary` parameter of a `multipart/form-data` content type. */
        fun boundaryOf(contentType: String?): String? {
            if (contentType == null) return null
            if (!contentType.lowercase(Locale.ROOT).startsWith("multipart/")) return null
            for (segment in contentType.split(';')) {
                val trimmed = segment.trim()
                if (!trimmed.lowercase(Locale.ROOT).startsWith("boundary")) continue
                val eq = trimmed.indexOf('=')
                if (eq < 0) continue
                val raw = trimmed.substring(eq + 1).trim()
                val value = if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                    raw.substring(1, raw.length - 1)
                } else {
                    raw
                }
                if (value.isNotEmpty()) return value
            }
            return null
        }

        /**
         * Turns a raw multipart file name into a safe relative path.
         *
         * Directory uploads send `folder/sub/file.txt`; Windows browsers may send a full
         * `C:\Users\...\file.txt`. Both are reduced to their harmless segments.
         */
        fun sanitizeRelativePath(rawName: String): List<String> {
            val withoutDrive = if (rawName.length > 2 && rawName[1] == ':') rawName.substring(2) else rawName
            return withoutDrive.split('/', '\\')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .map { segment -> segment.filter { it.code >= 0x20 }.trimEnd(' ') }
                .filter { it.isNotEmpty() }
        }
    }
}
