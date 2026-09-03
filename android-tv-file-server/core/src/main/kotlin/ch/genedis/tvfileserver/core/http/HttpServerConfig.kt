package ch.genedis.tvfileserver.core.http

import ch.genedis.tvfileserver.core.util.DEFAULT_BUFFER_BYTES
import java.net.InetAddress

/**
 * Tuning knobs for [HttpServer].
 *
 * The defaults target a low-end TV box: a couple of dozen connections is far more than a
 * household needs, and capping them keeps the thread pool and the heap predictable.
 */
data class HttpServerConfig(
    val port: Int = 8080,
    /** null binds the wildcard address, which is what a LAN server wants. */
    val bindAddress: InetAddress? = null,
    val backlog: Int = 64,
    val maxConnections: Int = 24,
    /** Time allowed to send the request line and headers of a *new* request. */
    val headerTimeoutMs: Int = 20_000,
    /** Time an idle keep-alive connection is held open. */
    val idleTimeoutMs: Int = 30_000,
    /** Time allowed for the handler plus body transfer. Uploads can legitimately be slow. */
    val bodyTimeoutMs: Int = 15 * 60_000,
    val maxHeaderBytes: Int = 32 * 1024,
    val maxRequestLineBytes: Int = 8 * 1024,
    val bufferSize: Int = DEFAULT_BUFFER_BYTES,
    val serverHeader: String = "TvFileServer",
    val tcpNoDelay: Boolean = true,
    val socketSendBuffer: Int = 256 * 1024,
)
