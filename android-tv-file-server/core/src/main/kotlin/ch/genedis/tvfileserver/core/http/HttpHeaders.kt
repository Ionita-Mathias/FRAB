package ch.genedis.tvfileserver.core.http

import java.util.Locale

/** Canonical header names used across the server. */
object HttpHeaderNames {
    const val ACCEPT = "Accept"
    const val ACCEPT_ENCODING = "Accept-Encoding"
    const val ACCEPT_RANGES = "Accept-Ranges"
    const val ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin"
    const val ALLOW = "Allow"
    const val AUTHORIZATION = "Authorization"
    const val CACHE_CONTROL = "Cache-Control"
    const val CONNECTION = "Connection"
    const val CONTENT_DISPOSITION = "Content-Disposition"
    const val CONTENT_ENCODING = "Content-Encoding"
    const val CONTENT_LENGTH = "Content-Length"
    const val CONTENT_RANGE = "Content-Range"
    const val CONTENT_TYPE = "Content-Type"
    const val COOKIE = "Cookie"
    const val DATE = "Date"
    const val DAV = "DAV"
    const val DEPTH = "Depth"
    const val DESTINATION = "Destination"
    const val ETAG = "ETag"
    const val EXPECT = "Expect"
    const val HOST = "Host"
    const val IF = "If"
    const val IF_MATCH = "If-Match"
    const val IF_MODIFIED_SINCE = "If-Modified-Since"
    const val IF_NONE_MATCH = "If-None-Match"
    const val IF_RANGE = "If-Range"
    const val LAST_MODIFIED = "Last-Modified"
    const val LOCATION = "Location"
    const val LOCK_TOKEN = "Lock-Token"
    const val MS_AUTHOR_VIA = "MS-Author-Via"
    const val OVERWRITE = "Overwrite"
    const val PRAGMA = "Pragma"
    const val RANGE = "Range"
    const val RETRY_AFTER = "Retry-After"
    const val SERVER = "Server"
    const val SET_COOKIE = "Set-Cookie"
    const val TIMEOUT = "Timeout"
    const val TRANSFER_ENCODING = "Transfer-Encoding"
    const val USER_AGENT = "User-Agent"
    const val WWW_AUTHENTICATE = "WWW-Authenticate"
    const val X_REQUESTED_WITH = "X-Requested-With"
}

/**
 * Case-insensitive, insertion-ordered, multi-valued header collection.
 *
 * Lookups fold the name to lower case; the casing used on first insertion is preserved when
 * the headers are written back out.
 */
class HttpHeaders() : Iterable<Pair<String, String>> {

    private val values = LinkedHashMap<String, MutableList<String>>()
    private val displayNames = LinkedHashMap<String, String>()

    constructor(initial: Map<String, String>) : this() {
        for ((name, value) in initial) set(name, value)
    }

    val size: Int get() = values.values.sumOf { it.size }

    val isEmpty: Boolean get() = values.isEmpty()

    /** Returns the first value recorded for [name], or null. */
    operator fun get(name: String): String? = values[key(name)]?.firstOrNull()

    /** Returns every value recorded for [name], in insertion order. */
    fun all(name: String): List<String> = values[key(name)]?.toList() ?: emptyList()

    /** Replaces every value previously recorded for [name]. */
    operator fun set(name: String, value: String) {
        val k = key(name)
        displayNames[k] = name
        values[k] = mutableListOf(value)
    }

    /** Appends [value] without dropping values already recorded for [name]. */
    fun add(name: String, value: String) {
        val k = key(name)
        displayNames.putIfAbsent(k, name)
        values.getOrPut(k) { mutableListOf() }.add(value)
    }

    /** Sets [name] to [value] only when no value has been recorded yet. */
    fun setIfAbsent(name: String, value: String) {
        if (!contains(name)) set(name, value)
    }

    fun remove(name: String) {
        val k = key(name)
        values.remove(k)
        displayNames.remove(k)
    }

    operator fun contains(name: String): Boolean = values.containsKey(key(name))

    fun getInt(name: String, fallback: Int = -1): Int = get(name)?.trim()?.toIntOrNull() ?: fallback

    fun getLong(name: String, fallback: Long = -1L): Long = get(name)?.trim()?.toLongOrNull() ?: fallback

    override fun iterator(): Iterator<Pair<String, String>> {
        val flattened = ArrayList<Pair<String, String>>(size)
        for ((k, list) in values) {
            val display = displayNames[k] ?: k
            for (value in list) flattened.add(display to value)
        }
        return flattened.iterator()
    }

    fun copy(): HttpHeaders {
        val copy = HttpHeaders()
        for ((name, value) in this) copy.add(name, value)
        return copy
    }

    override fun toString(): String = joinToString(", ") { "${it.first}: ${it.second}" }

    private fun key(name: String): String = name.lowercase(Locale.ROOT)
}
