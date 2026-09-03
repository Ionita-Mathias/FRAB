package ch.genedis.tvfileserver.core.http

import ch.genedis.tvfileserver.core.web.JsonWriter

/**
 * A response ready to be written to the wire.
 *
 * @param closeConnection forces the connection shut after this response even when the
 *   client asked for keep-alive (used for protocol errors and open-ended streams).
 */
class HttpResponse(
    val status: HttpStatus,
    val headers: HttpHeaders = HttpHeaders(),
    val body: HttpBody = HttpBody.Empty,
    val closeConnection: Boolean = false,
) {

    /** Sets a header and returns this response, for fluent construction. */
    fun header(name: String, value: String): HttpResponse {
        headers[name] = value
        return this
    }

    /** Adds a header without replacing existing values, and returns this response. */
    fun addHeader(name: String, value: String): HttpResponse {
        headers.add(name, value)
        return this
    }

    companion object {

        fun text(body: String, status: HttpStatus = HttpStatus.OK): HttpResponse =
            HttpResponse(status, contentTypeHeaders("text/plain; charset=utf-8"), HttpBody.of(body))

        fun html(body: String, status: HttpStatus = HttpStatus.OK): HttpResponse =
            HttpResponse(status, contentTypeHeaders("text/html; charset=utf-8"), HttpBody.of(body))

        fun json(body: String, status: HttpStatus = HttpStatus.OK): HttpResponse =
            HttpResponse(status, contentTypeHeaders("application/json; charset=utf-8"), HttpBody.of(body))
                .header(HttpHeaderNames.CACHE_CONTROL, "no-store")

        fun xml(body: String, status: HttpStatus = HttpStatus.MULTI_STATUS): HttpResponse =
            HttpResponse(status, contentTypeHeaders("application/xml; charset=\"utf-8\""), HttpBody.of(body))

        fun noContent(): HttpResponse = HttpResponse(HttpStatus.NO_CONTENT)

        fun status(status: HttpStatus): HttpResponse = HttpResponse(status)

        fun redirect(location: String, permanent: Boolean = false): HttpResponse =
            HttpResponse(if (permanent) HttpStatus.MOVED_PERMANENTLY else HttpStatus.SEE_OTHER)
                .header(HttpHeaderNames.LOCATION, location)

        /** A plain-text error payload. */
        fun error(status: HttpStatus, message: String = status.reason): HttpResponse =
            text("${status.code} ${status.reason}\n$message\n", status)
                .header(HttpHeaderNames.CACHE_CONTROL, "no-store")

        /** A JSON error payload of the shape the web UI expects. */
        fun jsonError(status: HttpStatus, message: String): HttpResponse =
            json(JsonWriter.obj { name("error").value(message) }, status)

        private fun contentTypeHeaders(contentType: String): HttpHeaders =
            HttpHeaders().apply { set(HttpHeaderNames.CONTENT_TYPE, contentType) }
    }
}

/** Synchronous request handler. Implementations run on an IO dispatcher and may block. */
fun interface HttpHandler {
    fun handle(request: HttpRequest): HttpResponse
}
