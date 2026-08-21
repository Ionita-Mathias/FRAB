package ch.genedis.tvfileserver.core.http

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * HTTP date formatting and parsing.
 *
 * Uses `java.time` (available from API 26, the module's minimum) so the formatters are
 * immutable and safe to share between connection threads — unlike `SimpleDateFormat`.
 */
object HttpDates {

    private val RFC_1123: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ROOT)
            .withZone(ZoneOffset.UTC)

    /** Obsolete RFC 850 / RFC 1036 form, e.g. `Sunday, 06-Nov-94 08:49:37 GMT`. */
    private val RFC_1036: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.ROOT)
            .withZone(ZoneOffset.UTC)

    /** ANSI C `asctime()` form, e.g. `Sun Nov  6 08:49:37 1994`. */
    private val ASCTIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.ROOT)
            .withZone(ZoneOffset.UTC)

    private val ISO_UTC: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC)

    /** Formats [epochMillis] as an RFC 1123 date in GMT, the form required by RFC 7231. */
    fun format(epochMillis: Long): String =
        RFC_1123.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC))

    /**
     * Parses any of the three date formats HTTP/1.1 requires servers to accept.
     *
     * @return epoch milliseconds, or null when [value] is absent or unparseable.
     */
    fun parse(value: String?): Long? {
        val text = value?.trim()
        if (text.isNullOrEmpty()) return null
        for (formatter in arrayOf(RFC_1123, RFC_1036, ASCTIME)) {
            try {
                return ZonedDateTime.parse(text, formatter).toInstant().toEpochMilli()
            } catch (ignored: DateTimeParseException) {
                // Try the next accepted format.
            }
        }
        return null
    }

    /** Formats [epochMillis] as an ISO-8601 UTC timestamp, used by the WebDAV `creationdate`. */
    fun formatIso(epochMillis: Long): String =
        ISO_UTC.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC))
}
