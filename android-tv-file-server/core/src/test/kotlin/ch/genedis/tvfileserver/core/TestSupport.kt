package ch.genedis.tvfileserver.core

import ch.genedis.tvfileserver.core.vfs.LocalFileSystem
import ch.genedis.tvfileserver.core.vfs.VfsRoot
import ch.genedis.tvfileserver.core.vfs.VfsRootType
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale

/** A parsed response from [httpRequest]. */
class TestResponse(
    val statusLine: String,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    val status: Int get() = statusLine.split(' ')[1].toInt()
    val text: String get() = String(body, Charsets.UTF_8)

    fun header(name: String): String? =
        headers[name.lowercase(Locale.ROOT)]?.firstOrNull()

    fun headerAll(name: String): List<String> = headers[name.lowercase(Locale.ROOT)] ?: emptyList()

    override fun toString(): String = "$statusLine (${body.size} bytes)"
}

/** Grabs a port that is free right now. Racy in theory, reliable in a test sandbox. */
fun freePort(): Int = ServerSocket(0).use { it.localPort }

/**
 * A deliberately dumb blocking HTTP client.
 *
 * Using `HttpURLConnection` would hide exactly the framing bugs these tests exist to catch,
 * so the request is written by hand and the response is parsed byte by byte.
 */
fun httpRequest(
    port: Int,
    method: String,
    path: String,
    headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
    keepAlive: Boolean = false,
    socket: Socket? = null,
): TestResponse {
    val client = socket ?: Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 15_000 }
    val out = client.getOutputStream()
    writeRequest(out, method, path, headers, body, keepAlive)
    val response = readResponse(BufferedInputStream(client.getInputStream()), method)
    if (socket == null) client.close()
    return response
}

fun writeRequest(
    out: OutputStream,
    method: String,
    path: String,
    headers: Map<String, String>,
    body: ByteArray?,
    keepAlive: Boolean,
) {
    val builder = StringBuilder()
    builder.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
    builder.append("Host: 127.0.0.1\r\n")
    builder.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
    for ((name, value) in headers) builder.append(name).append(": ").append(value).append("\r\n")
    if (body != null && !headers.keys.any { it.equals("Transfer-Encoding", true) }) {
        builder.append("Content-Length: ").append(body.size).append("\r\n")
    }
    builder.append("\r\n")
    out.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
    if (body != null) out.write(body)
    out.flush()
}

fun readResponse(input: InputStream, method: String): TestResponse {
    val statusLine = readAsciiLine(input) ?: error("No status line")
    val headers = LinkedHashMap<String, MutableList<String>>()
    while (true) {
        val line = readAsciiLine(input) ?: break
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        headers.getOrPut(line.substring(0, colon).trim().lowercase(Locale.ROOT)) { mutableListOf() }
            .add(line.substring(colon + 1).trim())
    }

    val status = statusLine.split(' ')[1].toInt()
    val bodyless = method == "HEAD" || status == 204 || status == 304 || status < 200
    if (bodyless) return TestResponse(statusLine, headers, ByteArray(0))

    val transferEncoding = headers["transfer-encoding"]?.firstOrNull()?.lowercase(Locale.ROOT)
    val body = when {
        transferEncoding != null && transferEncoding.contains("chunked") -> readChunked(input)
        headers.containsKey("content-length") -> {
            val length = headers["content-length"]!!.first().trim().toInt()
            val data = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(data, read, length - read)
                if (n < 0) break
                read += n
            }
            if (read == length) data else data.copyOf(read)
        }
        else -> input.readBytes()
    }
    return TestResponse(statusLine, headers, body)
}

private fun readChunked(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    while (true) {
        val sizeLine = readAsciiLine(input) ?: break
        val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
        if (size == 0) {
            while (true) {
                val trailer = readAsciiLine(input) ?: break
                if (trailer.isEmpty()) break
            }
            break
        }
        val chunk = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(chunk, read, size - read)
            if (n < 0) break
            read += n
        }
        out.write(chunk, 0, read)
        readAsciiLine(input)
    }
    return out.toByteArray()
}

fun readAsciiLine(input: InputStream): String? {
    val builder = StringBuilder(64)
    while (true) {
        val value = input.read()
        if (value < 0) return if (builder.isEmpty()) null else builder.toString()
        if (value == '\n'.code) return builder.toString()
        if (value != '\r'.code) builder.append(value.toChar())
    }
}

/** Builds a single-root [LocalFileSystem] over [directory]. */
fun testFileSystem(
    directory: File,
    writable: Boolean = true,
    readOnly: Boolean = false,
    rootId: String = "data",
): LocalFileSystem = LocalFileSystem(
    roots = listOf(
        VfsRoot(
            id = rootId,
            displayName = "Test storage",
            directory = directory,
            type = VfsRootType.INTERNAL,
            writable = writable,
        ),
    ),
    readOnly = readOnly,
)

/** Builds a `multipart/form-data` body from ordered field/file parts. */
class MultipartBuilder(val boundary: String = "----TvFileServerTestBoundary") {

    private val out = ByteArrayOutputStream()

    fun field(name: String, value: String): MultipartBuilder = apply {
        out.write("--$boundary\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
    }

    fun file(
        name: String,
        fileName: String,
        content: ByteArray,
        contentType: String = "application/octet-stream",
        rawDisposition: String? = null,
    ): MultipartBuilder = apply {
        val disposition = rawDisposition
            ?: "form-data; name=\"$name\"; filename=\"$fileName\""
        out.write("--$boundary\r\n".toByteArray(Charsets.ISO_8859_1))
        out.write("Content-Disposition: $disposition\r\n".toByteArray(Charsets.UTF_8))
        out.write("Content-Type: $contentType\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(content)
        out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
    }

    fun build(): ByteArray {
        val copy = ByteArrayOutputStream()
        copy.write(out.toByteArray())
        copy.write("--$boundary--\r\n".toByteArray(Charsets.ISO_8859_1))
        return copy.toByteArray()
    }

    val contentType: String get() = "multipart/form-data; boundary=$boundary"
}

/** Polls [condition] until it holds or [timeoutMs] elapses. */
fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(20)
    }
    throw AssertionError("Condition did not become true within $timeoutMs ms")
}
