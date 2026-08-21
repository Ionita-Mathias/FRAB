package ch.genedis.tvfileserver.core.http

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * HTTP date formatting and parsing.
 *
 * Uses `java.time` (available from API 26, the module's minimum) so the formatters are
 * immutable and safe to share between connection threads — unlike `SimpleDateFormat`.
 *
 * The locale is pinned to English because HTTP dates are defined in English and
 * `Locale.ROOT` has no full-text day names, which the obsolete RFC 1036 form needs.
 */
object HttpDates {

    private val RFC_1123: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC)

    /**
     * Obsolete RFC 850 / RFC 1036 form, e.g. `Sunday, 06-Nov-94 08:49:37 GMT`.
     *
     * The two-digit year needs an explicit base value, otherwise "94" parses as year 94.
     */
    private val RFC_1036: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("EEEE, dd-MMM-")
        .appendValueReduced(ChronoField.YEAR, 2, 2, 1970)
        .appendPattern(" HH:mm:ss zzz")
        .toFormatter(Locale.ENGLISH)
        .withZone(ZoneOffset.UTC)

    /** ANSI C `asctime()` form, e.g. `Sun Nov  6 08:49:37 1994`. Carries no zone. */
    private val ASCTIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.ENGLISH)

    private val ISO_UTC: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH)
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

        for (formatter in arrayOf(DateTimeFormatter.RFC_1123_DATE_TIME, RFC_1123, RFC_1036)) {
            try {
                return ZonedDateTime.parse(text, formatter).toInstant().toEpochMilli()
            } catch (ignored: DateTimeParseException) {
                // Try the next accepted format.
            }
        }
        // asctime() carries no zone at all, so it is resolved as a local time in UTC.
        try {
            return LocalDateTime.parse(text, ASCTIME).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (ignored: DateTimeParseException) {
            return null
        }
    }

    /** Formats [epochMillis] as an ISO-8601 UTC timestamp, used by the WebDAV `creationdate`. */
    fun formatIso(epochMillis: Long): String =
        ISO_UTC.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC))
}
