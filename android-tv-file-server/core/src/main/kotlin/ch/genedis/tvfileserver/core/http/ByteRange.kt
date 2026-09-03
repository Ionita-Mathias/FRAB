package ch.genedis.tvfileserver.core.http

/** An inclusive byte range within a resource. */
data class ByteRange(val start: Long, val endInclusive: Long) {
    init {
        require(start >= 0) { "start must not be negative" }
        require(endInclusive >= start) { "end must not precede start" }
    }

    val length: Long get() = endInclusive - start + 1
}

/**
 * Parser for the `Range` request header (RFC 7233).
 *
 * The server only ever *serves* the first range of a multi-range request; that is legal
 * (a server may always answer with the whole entity or a single range) and avoids the
 * `multipart/byteranges` machinery, which no media player needs.
 */
object RangeParser {

    private const val PREFIX = "bytes="

    /**
     * @return null when the header is absent or does not use the `bytes` unit — the caller
     *   should then serve the whole entity. An empty list means the header was present but
     *   every range was unsatisfiable, which must be answered with `416`.
     */
    fun parse(headerValue: String?, resourceLength: Long): List<ByteRange>? {
        val header = headerValue?.trim() ?: return null
        if (!header.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)) return null
        val spec = header.substring(PREFIX.length)
        if (spec.isEmpty()) return null

        val ranges = ArrayList<ByteRange>(2)
        var sawValidSyntax = false
        for (rawPart in spec.split(',')) {
            val part = rawPart.trim()
            if (part.isEmpty()) continue
            val dash = part.indexOf('-')
            if (dash < 0) return null

            val firstText = part.substring(0, dash).trim()
            val lastText = part.substring(dash + 1).trim()

            if (firstText.isEmpty()) {
                // Suffix range: the last N bytes.
                val suffix = lastText.toLongOrNull() ?: return null
                sawValidSyntax = true
                if (suffix <= 0 || resourceLength <= 0) continue
                val start = if (suffix >= resourceLength) 0L else resourceLength - suffix
                ranges.add(ByteRange(start, resourceLength - 1))
            } else {
                val start = firstText.toLongOrNull() ?: return null
                sawValidSyntax = true
                if (resourceLength <= 0 || start >= resourceLength) continue
                val end = if (lastText.isEmpty()) {
                    resourceLength - 1
                } else {
                    val parsed = lastText.toLongOrNull() ?: return null
                    if (parsed < start) continue
                    minOf(parsed, resourceLength - 1)
                }
                ranges.add(ByteRange(start, end))
            }
        }
        if (!sawValidSyntax) return null
        return ranges
    }

    /** Builds the `Content-Range` value for a satisfied range. */
    fun contentRange(range: ByteRange, resourceLength: Long): String =
        "bytes ${range.start}-${range.endInclusive}/$resourceLength"

    /** Builds the `Content-Range` value for a `416` response. */
    fun unsatisfiedContentRange(resourceLength: Long): String = "bytes */$resourceLength"
}
