package ch.genedis.tvfileserver.core.http

import java.io.OutputStream
import java.nio.charset.Charset

/**
 * Response payload.
 *
 * File transfers use [Streaming] so a multi-gigabyte download never lands in the heap.
 */
sealed class HttpBody {

    /** No payload at all. */
    object Empty : HttpBody()

    /** A fully materialised payload. Use only for small responses (JSON, HTML, errors). */
    class Bytes(val data: ByteArray) : HttpBody()

    /**
     * A payload produced on demand.
     *
     * @param contentLength the exact length when known, which lets the server use
     *   `Content-Length` and keep the connection alive; null selects chunked encoding.
     * @param writer invoked once with the response stream. It must not close the stream.
     */
    class Streaming(val contentLength: Long?, val writer: (OutputStream) -> Unit) : HttpBody()

    companion object {
        fun of(text: String, charset: Charset = Charsets.UTF_8): Bytes = Bytes(text.toByteArray(charset))
    }
}
