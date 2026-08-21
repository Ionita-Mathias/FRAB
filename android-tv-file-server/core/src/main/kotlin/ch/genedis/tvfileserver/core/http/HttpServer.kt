package ch.genedis.tvfileserver.core.http

import ch.genedis.tvfileserver.core.util.CoreLog
import ch.genedis.tvfileserver.core.util.closeQuietly
import ch.genedis.tvfileserver.core.util.copyStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A blocking HTTP/1.1 server driven by coroutines.
 *
 * Why not Ktor or NanoHTTPD? Ktor's Netty/CIO engines cost several megabytes of APK and a
 * lot of heap on a 2 GB device, and NanoHTTPD spools request bodies to temporary files
 * before a handler ever sees them — fatal for multi-gigabyte uploads on a TV box with little
 * free space. This engine streams both directions with a fixed per-connection buffer.
 *
 * Concurrency model: one coroutine accepts, each connection is handled by a coroutine on a
 * bounded view of [Dispatchers.IO]. Blocking socket reads inside those coroutines are fine
 * because the dispatcher is sized for them.
 */
class HttpServer(
    private val config: HttpServerConfig,
    private val handler: HttpHandler,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val connectionDispatcher = Dispatchers.IO.limitedParallelism(config.maxConnections)

    private val liveSockets: MutableSet<Socket> =
        Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val liveConnections = AtomicInteger(0)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    private var stopped = false

    /** The port actually bound, which differs from the configured one when port 0 is used. */
    @Volatile
    var boundPort: Int = -1
        private set

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    val activeConnections: Int get() = liveConnections.get()

    /**
     * Binds the listening socket synchronously and then accepts in [scope].
     *
     * Binding synchronously means a port conflict surfaces to the caller (and therefore to
     * the TV UI) instead of disappearing into a coroutine.
     *
     * @throws IOException when the port cannot be bound.
     */
    fun start(scope: CoroutineScope) {
        check(serverSocket == null) { "Server already started" }
        stopped = false
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(config.bindAddress, config.port), config.backlog)
        } catch (error: IOException) {
            closeQuietly(socket)
            throw IOException("Cannot bind HTTP port ${config.port}: ${error.message}", error)
        }
        serverSocket = socket
        boundPort = socket.localPort
        CoreLog.i(TAG, "HTTP server listening on port $boundPort")

        acceptJob = scope.launch(Dispatchers.IO) {
            acceptLoop(socket, this)
        }
    }

    /** Closes the listener and every live connection. Idempotent. */
    fun stop() {
        if (stopped) return
        stopped = true
        val socket = serverSocket
        serverSocket = null
        boundPort = -1
        closeQuietly(socket)
        acceptJob?.cancel()
        acceptJob = null
        for (client in liveSockets.toList()) closeQuietly(client)
        liveSockets.clear()
        CoreLog.i(TAG, "HTTP server stopped")
    }

    private fun acceptLoop(socket: ServerSocket, scope: CoroutineScope) {
        while (!stopped && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (error: IOException) {
                if (stopped || socket.isClosed) break
                CoreLog.w(TAG, "accept() failed, continuing", error)
                continue
            }
            if (liveConnections.get() >= config.maxConnections) {
                rejectOverloaded(client)
                continue
            }
            liveConnections.incrementAndGet()
            liveSockets.add(client)
            scope.launch(connectionDispatcher) {
                try {
                    serveConnection(client)
                } catch (error: Throwable) {
                    CoreLog.w(TAG, "Connection handler crashed", error)
                } finally {
                    liveSockets.remove(client)
                    liveConnections.decrementAndGet()
                    closeQuietly(client)
                }
            }
        }
    }

    /** Answers 503 without occupying a dispatcher slot, so overload stays cheap. */
    private fun rejectOverloaded(client: Socket) {
        try {
            client.getOutputStream().apply {
                write(OVERLOADED_RESPONSE)
                flush()
            }
        } catch (error: IOException) {
            CoreLog.d(TAG, "Could not write the 503 overload response: ${error.message}")
        } finally {
            closeQuietly(client)
        }
    }

    private fun serveConnection(client: Socket) {
        client.tcpNoDelay = config.tcpNoDelay
        try {
            client.sendBufferSize = config.socketSendBuffer
        } catch (error: SocketException) {
            CoreLog.d(TAG, "Cannot set the socket send buffer: ${error.message}")
        }

        val input = BufferedInputStream(client.getInputStream(), config.bufferSize)
        val output = BufferedOutputStream(client.getOutputStream(), config.bufferSize)
        val remote = client.inetAddress?.hostAddress ?: "unknown"
        val local = client.localAddress?.hostAddress ?: "unknown"
        var served = 0

        while (!stopped && !client.isClosed) {
            client.soTimeout = if (served == 0) config.headerTimeoutMs else config.idleTimeoutMs
            val head = try {
                readRequestHead(input)
            } catch (error: SocketTimeoutException) {
                CoreLog.d(TAG, "Idle connection from $remote timed out")
                return
            } catch (error: RequestFormatException) {
                writeSimpleError(output, error.status, error.message ?: "Bad request")
                return
            } catch (error: IOException) {
                CoreLog.d(TAG, "Connection from $remote ended: ${error.message}")
                return
            } ?: return // clean EOF between requests

            client.soTimeout = config.bodyTimeoutMs
            val bodyStream = frameBody(head, input)
            val request = HttpRequest(
                method = head.method,
                rawTarget = head.target,
                path = head.path,
                rawPath = head.rawPath,
                queryParams = head.query,
                headers = head.headers,
                body = bodyStream,
                protocol = head.protocol,
                remoteAddress = remote,
                localAddress = local,
                localPort = client.localPort,
            )

            if (head.expectContinue) {
                output.write(CONTINUE_RESPONSE)
                output.flush()
            }

            val response = try {
                handler.handle(request)
            } catch (error: Throwable) {
                CoreLog.e(TAG, "Handler failed for ${head.method} ${head.path}", error)
                HttpResponse.error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error")
            }

            val keepAlive = request.isKeepAlive && !response.closeConnection && !stopped
            val written = try {
                writeResponse(output, head, response, keepAlive)
            } catch (error: IOException) {
                // The client went away mid-download; that is normal for video seeking.
                CoreLog.d(TAG, "Client ${remote} dropped during the response: ${error.message}")
                return
            }
            served++

            if (!keepAlive || !written) return
            if (!drainBody(bodyStream)) return
        }
    }

    // ------------------------------------------------------------------ request parsing

    private class RequestHead(
        val method: String,
        val target: String,
        val protocol: String,
        val headers: HttpHeaders,
        val path: String,
        val rawPath: String,
        val query: Map<String, List<String>>,
        val expectContinue: Boolean,
    )

    private class RequestFormatException(val status: HttpStatus, message: String) : IOException(message)

    /** @return null on a clean EOF, i.e. the peer closed an idle keep-alive connection. */
    private fun readRequestHead(input: InputStream): RequestHead? {
        var line = readLine(input, config.maxRequestLineBytes) ?: return null
        // Tolerate leading blank lines, which some clients send after a keep-alive response.
        while (line.isEmpty()) {
            line = readLine(input, config.maxRequestLineBytes) ?: return null
        }

        val firstSpace = line.indexOf(' ')
        val lastSpace = line.lastIndexOf(' ')
        if (firstSpace <= 0 || lastSpace <= firstSpace) {
            throw RequestFormatException(HttpStatus.BAD_REQUEST, "Malformed request line")
        }
        val method = line.substring(0, firstSpace).uppercase(Locale.ROOT)
        val target = line.substring(firstSpace + 1, lastSpace)
        val protocol = line.substring(lastSpace + 1).uppercase(Locale.ROOT)
        if (!protocol.startsWith("HTTP/")) {
            throw RequestFormatException(HttpStatus.BAD_REQUEST, "Unsupported protocol")
        }

        val headers = HttpHeaders()
        var headerBytes = 0
        while (true) {
            val headerLine = readLine(input, config.maxRequestLineBytes)
                ?: throw RequestFormatException(HttpStatus.BAD_REQUEST, "Truncated headers")
            if (headerLine.isEmpty()) break
            headerBytes += headerLine.length + 2
            if (headerBytes > config.maxHeaderBytes) {
                throw RequestFormatException(HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE, "Headers too large")
            }
            if (headerLine[0] == ' ' || headerLine[0] == '\t') {
                // Obsolete line folding: RFC 7230 lets a server reject it, and accepting it is
                // a known request-smuggling vector.
                throw RequestFormatException(HttpStatus.BAD_REQUEST, "Obsolete header folding")
            }
            val colon = headerLine.indexOf(':')
            if (colon <= 0) {
                throw RequestFormatException(HttpStatus.BAD_REQUEST, "Malformed header")
            }
            headers.add(headerLine.substring(0, colon).trim(), headerLine.substring(colon + 1).trim())
        }

        val (rawPath, rawQuery) = HttpRequest.splitTarget(normaliseTarget(target))
        val decodedPath = HttpRequest.normalizePath(UrlCodec.decode(rawPath))
        val expectContinue = headers[HttpHeaderNames.EXPECT]
            ?.lowercase(Locale.ROOT)
            ?.contains("100-continue") == true

        return RequestHead(
            method = method,
            target = target,
            protocol = protocol,
            headers = headers,
            path = decodedPath,
            rawPath = rawPath,
            query = UrlCodec.parseQuery(rawQuery),
            expectContinue = expectContinue,
        )
    }

    /** Strips the `http://host` prefix some proxies and WebDAV clients send in absolute form. */
    private fun normaliseTarget(target: String): String {
        if (!target.startsWith("http://", ignoreCase = true) &&
            !target.startsWith("https://", ignoreCase = true)
        ) {
            return target
        }
        val schemeEnd = target.indexOf("://") + 3
        val slash = target.indexOf('/', schemeEnd)
        return if (slash < 0) "/" else target.substring(slash)
    }

    /** Reads one CRLF- or LF-terminated line as US-ASCII. */
    private fun readLine(input: InputStream, limit: Int): String? {
        val builder = StringBuilder(64)
        while (true) {
            val value = input.read()
            if (value < 0) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) return builder.toString()
            if (value != '\r'.code) builder.append(value.toChar())
            if (builder.length > limit) {
                throw RequestFormatException(HttpStatus.URI_TOO_LONG, "Line exceeds $limit bytes")
            }
        }
    }

    private fun frameBody(head: RequestHead, input: InputStream): InputStream {
        val transferEncoding = head.headers[HttpHeaderNames.TRANSFER_ENCODING]?.lowercase(Locale.ROOT)
        if (transferEncoding != null && transferEncoding.contains("chunked")) {
            return ChunkedInputStream(input)
        }
        val length = head.headers.getLong(HttpHeaderNames.CONTENT_LENGTH, -1L)
        return if (length > 0) LimitedInputStream(input, length) else EMPTY_BODY
    }

    /**
     * Consumes whatever the handler left unread so the next request starts at the right
     * offset. A large leftover means abandoning the connection is cheaper than draining it.
     */
    private fun drainBody(body: InputStream): Boolean {
        if (body === EMPTY_BODY) return true
        return try {
            val scratch = ByteArray(8192)
            val drained = copyStream(body, NullOutputStream, scratch, limit = MAX_DRAIN_BYTES + 1)
            drained <= MAX_DRAIN_BYTES
        } catch (error: IOException) {
            false
        }
    }

    // ------------------------------------------------------------------ response writing

    /** @return false when the connection must not be reused. */
    private fun writeResponse(
        output: OutputStream,
        head: RequestHead,
        response: HttpResponse,
        keepAlive: Boolean,
    ): Boolean {
        val headers = response.headers
        val status = response.status
        val bodyAllowed = status.code != HttpStatus.NO_CONTENT.code &&
            status.code != HttpStatus.NOT_MODIFIED.code &&
            status.code >= 200
        val isHead = head.method == "HEAD"

        headers.setIfAbsent(HttpHeaderNames.DATE, HttpDates.format(System.currentTimeMillis()))
        headers.setIfAbsent(HttpHeaderNames.SERVER, config.serverHeader)

        var chunked = false
        if (!bodyAllowed) {
            headers.remove(HttpHeaderNames.CONTENT_LENGTH)
            headers.remove(HttpHeaderNames.TRANSFER_ENCODING)
        } else {
            when (val body = response.body) {
                is HttpBody.Empty -> headers[HttpHeaderNames.CONTENT_LENGTH] = "0"
                is HttpBody.Bytes -> headers[HttpHeaderNames.CONTENT_LENGTH] = body.data.size.toString()
                is HttpBody.Streaming -> {
                    val length = body.contentLength
                    if (length != null) {
                        headers[HttpHeaderNames.CONTENT_LENGTH] = length.toString()
                    } else if (head.protocol == "HTTP/1.1") {
                        headers[HttpHeaderNames.TRANSFER_ENCODING] = "chunked"
                        headers.remove(HttpHeaderNames.CONTENT_LENGTH)
                        chunked = true
                    } else {
                        // HTTP/1.0 has no chunked encoding: the body ends when we close.
                        headers.remove(HttpHeaderNames.CONTENT_LENGTH)
                    }
                }
            }
        }

        val closeAfter = !keepAlive || (!chunked && bodyAllowed && isOpenEnded(response, headers))
        headers[HttpHeaderNames.CONNECTION] = if (closeAfter) "close" else "keep-alive"

        val head1 = StringBuilder(256)
        head1.append(head.protocol).append(' ').append(status.code).append(' ').append(status.reason)
            .append("\r\n")
        for ((name, value) in headers) {
            head1.append(name).append(": ").append(value).append("\r\n")
        }
        head1.append("\r\n")
        output.write(head1.toString().toByteArray(Charsets.ISO_8859_1))

        if (!bodyAllowed || isHead) {
            output.flush()
            return !closeAfter
        }

        when (val body = response.body) {
            is HttpBody.Empty -> Unit
            is HttpBody.Bytes -> output.write(body.data)
            is HttpBody.Streaming -> {
                if (chunked) {
                    val chunkedOut = ChunkedOutputStream(output)
                    body.writer(chunkedOut)
                    chunkedOut.finish()
                } else {
                    body.writer(output)
                }
            }
        }
        output.flush()
        return !closeAfter
    }

    /** A length-less HTTP/1.0 body can only be delimited by closing the connection. */
    private fun isOpenEnded(response: HttpResponse, headers: HttpHeaders): Boolean =
        response.body is HttpBody.Streaming &&
            !headers.contains(HttpHeaderNames.CONTENT_LENGTH) &&
            !headers.contains(HttpHeaderNames.TRANSFER_ENCODING)

    private fun writeSimpleError(output: OutputStream, status: HttpStatus, message: String) {
        try {
            val payload = "${status.code} ${status.reason}\n$message\n".toByteArray(Charsets.UTF_8)
            val header = buildString {
                append("HTTP/1.1 ").append(status.code).append(' ').append(status.reason).append("\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n")
                append("Content-Length: ").append(payload.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(header.toByteArray(Charsets.ISO_8859_1))
            output.write(payload)
            output.flush()
        } catch (error: IOException) {
            CoreLog.d(TAG, "Could not write the error response: ${error.message}")
        }
    }

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private companion object {
        const val TAG = "HttpServer"
        const val MAX_DRAIN_BYTES = 1L shl 20

        val EMPTY_BODY: InputStream = ByteArrayInputStream(ByteArray(0))

        val CONTINUE_RESPONSE = "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        val OVERLOADED_RESPONSE = (
            "HTTP/1.1 503 Service Unavailable\r\n" +
                "Content-Length: 0\r\n" +
                "Retry-After: 2\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
    }
}
