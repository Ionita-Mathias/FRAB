package ch.genedis.tvfileserver.core.http

import ch.genedis.tvfileserver.core.util.readAtMost
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale

/**
 * A parsed HTTP request.
 *
 * [body] is already framed by the server (length-limited or de-chunked), so handlers can
 * read it directly without worrying about the transfer encoding.
 */
class HttpRequest(
    val method: String,
    val rawTarget: String,
    val path: String,
    val rawPath: String,
    val queryParams: Map<String, List<String>>,
    val headers: HttpHeaders,
    val body: InputStream,
    val protocol: String,
    val remoteAddress: String,
    val localAddress: String,
    val localPort: Int,
    val basePath: String = "",
) {

    /** Declared body length, or -1 when the body is chunked or absent. */
    val contentLength: Long get() = headers.getLong(HttpHeaderNames.CONTENT_LENGTH, -1L)

    val contentType: String? get() = headers[HttpHeaderNames.CONTENT_TYPE]

    /** Whether the connection may be reused after this request. */
    val isKeepAlive: Boolean
        get() {
            val connection = headers[HttpHeaderNames.CONNECTION]?.lowercase(Locale.ROOT)
            return when {
                connection != null && connection.contains("close") -> false
                connection != null && connection.contains("keep-alive") -> true
                else -> protocol.endsWith("1.1")
            }
        }

    fun header(name: String): String? = headers[name]

    fun query(name: String): String? = queryParams[name]?.firstOrNull()

    fun queryAll(name: String): List<String> = queryParams[name] ?: emptyList()

    /** Reads a single cookie value from the `Cookie` header. */
    fun cookie(name: String): String? {
        val raw = headers[HttpHeaderNames.COOKIE] ?: return null
        for (part in raw.split(';')) {
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            if (trimmed.substring(0, eq).trim() == name) {
                return trimmed.substring(eq + 1).trim().removeSurrounding("\"")
            }
        }
        return null
    }

    /**
     * Reads the whole body into memory.
     *
     * @throws java.io.IOException when the body exceeds [limit].
     */
    fun bodyBytes(limit: Long = DEFAULT_BODY_LIMIT): ByteArray = body.readAtMost(limit)

    fun bodyText(limit: Long = DEFAULT_BODY_LIMIT, charset: Charset = Charsets.UTF_8): String =
        String(bodyBytes(limit), charset)

    /**
     * Parses an `application/x-www-form-urlencoded` body. The result is cached, so calling
     * this twice is safe even though the body stream can only be consumed once.
     */
    fun formParams(): Map<String, List<String>> {
        cachedForm?.let { return it }
        val type = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val parsed = if (type == "application/x-www-form-urlencoded") {
            UrlCodec.parseQuery(bodyText(DEFAULT_FORM_LIMIT))
        } else {
            emptyMap()
        }
        cachedForm = parsed
        return parsed
    }

    fun form(name: String): String? = formParams()[name]?.firstOrNull()

    /** Query parameter first, then form field. */
    fun param(name: String): String? = query(name) ?: form(name)

    /** Absolute origin of this request, derived from the `Host` header when present. */
    fun originUrl(): String {
        val host = headers[HttpHeaderNames.HOST]?.trim()
        return if (!host.isNullOrEmpty()) "http://$host" else "http://$localAddress:$localPort"
    }

    /** Returns a copy of this request re-rooted under [newBasePath]. Used by `Router.mount`. */
    fun withBasePath(newBasePath: String, newPath: String): HttpRequest = HttpRequest(
        method = method,
        rawTarget = rawTarget,
        path = newPath,
        rawPath = rawPath,
        queryParams = queryParams,
        headers = headers,
        body = body,
        protocol = protocol,
        remoteAddress = remoteAddress,
        localAddress = localAddress,
        localPort = localPort,
        basePath = newBasePath,
    ).also { it.cachedForm = cachedForm }

    private var cachedForm: Map<String, List<String>>? = null

    companion object {
        const val DEFAULT_BODY_LIMIT: Long = 1L shl 20
        const val DEFAULT_FORM_LIMIT: Long = 256L * 1024

        /** Splits a request target into its raw path and raw query components. */
        fun splitTarget(rawTarget: String): Pair<String, String?> {
            val hash = rawTarget.indexOf('#')
            val withoutFragment = if (hash >= 0) rawTarget.substring(0, hash) else rawTarget
            val mark = withoutFragment.indexOf('?')
            return if (mark < 0) {
                withoutFragment to null
            } else {
                withoutFragment.substring(0, mark) to withoutFragment.substring(mark + 1)
            }
        }

        /**
         * Normalises an already percent-decoded path: collapses repeated separators, applies
         * `.` and `..` without ever escaping the root, and strips NUL bytes.
         */
        fun normalizePath(decoded: String): String {
            val cleaned = decoded.replace("\u0000", "")
            val segments = ArrayList<String>(8)
            for (segment in cleaned.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                    else -> segments.add(segment)
                }
            }
            val trailingSlash = cleaned.length > 1 && cleaned.endsWith('/')
            if (segments.isEmpty()) return "/"
            val joined = "/" + segments.joinToString("/")
            return if (trailingSlash) "$joined/" else joined
        }
    }
}
