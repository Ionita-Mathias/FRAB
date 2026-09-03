package ch.genedis.tvfileserver.core.http

import java.io.ByteArrayOutputStream

/**
 * Percent-encoding helpers.
 *
 * `java.net.URLEncoder`/`URLDecoder` implement the `application/x-www-form-urlencoded`
 * rules, which mangle `/` and `+` inside path segments. These helpers implement RFC 3986
 * instead and never throw on malformed input — a file server must stay usable when a client
 * sends a slightly wrong escape sequence.
 */
object UrlCodec {

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private val HEX = "0123456789ABCDEF".toCharArray()

    /**
     * Percent-decodes [value] as UTF-8.
     *
     * A `%` that is not followed by two hexadecimal digits is emitted literally rather than
     * rejected. When [plusAsSpace] is true, `+` decodes to a space (query-string semantics).
     */
    fun decode(value: String, plusAsSpace: Boolean = false): String {
        if (value.indexOf('%') < 0 && (!plusAsSpace || value.indexOf('+') < 0)) return value
        val out = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            when {
                ch == '%' && index + 2 < value.length &&
                    isHex(value[index + 1]) && isHex(value[index + 2]) -> {
                    out.write((hexValue(value[index + 1]) shl 4) or hexValue(value[index + 2]))
                    index += 3
                }
                ch == '+' && plusAsSpace -> {
                    out.write(' '.code)
                    index++
                }
                ch.code < 0x80 -> {
                    out.write(ch.code)
                    index++
                }
                else -> {
                    // Raw non-ASCII in a URL is illegal but common; pass it through as UTF-8.
                    val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                    out.write(bytes, 0, bytes.size)
                    index++
                }
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Percent-encodes everything except the RFC 3986 unreserved set and `/`. */
    fun encodePath(value: String): String = encode(value, "/")

    /** Percent-encodes everything except the RFC 3986 unreserved set. */
    fun encodeComponent(value: String): String = encode(value, "")

    /**
     * Parses a raw query string into a multimap. `a=1&a=2&flag` yields
     * `{a: [1, 2], flag: [""]}`. A null or blank input yields an empty map.
     */
    fun parseQuery(rawQuery: String?): Map<String, List<String>> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, MutableList<String>>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val name: String
            val value: String
            if (eq < 0) {
                name = decode(pair, plusAsSpace = true)
                value = ""
            } else {
                name = decode(pair.substring(0, eq), plusAsSpace = true)
                value = decode(pair.substring(eq + 1), plusAsSpace = true)
            }
            if (name.isEmpty()) continue
            result.getOrPut(name) { mutableListOf() }.add(value)
        }
        return result
    }

    private fun encode(value: String, extraSafe: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val builder = StringBuilder(bytes.size + 16)
        for (byte in bytes) {
            val ch = (byte.toInt() and 0xFF).toChar()
            if (UNRESERVED.indexOf(ch) >= 0 || extraSafe.indexOf(ch) >= 0) {
                builder.append(ch)
            } else {
                builder.append('%')
                builder.append(HEX[(byte.toInt() shr 4) and 0x0F])
                builder.append(HEX[byte.toInt() and 0x0F])
            }
        }
        return builder.toString()
    }

    private fun isHex(ch: Char): Boolean =
        (ch in '0'..'9') || (ch in 'a'..'f') || (ch in 'A'..'F')

    private fun hexValue(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        else -> ch - 'A' + 10
    }
}
