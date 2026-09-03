package ch.genedis.tvfileserver.ui

import java.util.Locale

/** Human-readable formatting for sizes, speeds and durations. */
object UiFormat {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

    /** Formats a byte count, e.g. `1.4 GB`. Values below a kilobyte keep no decimals. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes ${UNITS[0]}"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < UNITS.size - 1) {
            value /= 1024
            unit++
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, UNITS[unit])
    }

    /** Formats a throughput, e.g. `12.4 MB/s`. */
    fun formatSpeed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0) "—" else "${formatBytes(bytesPerSecond)}/s"

    /**
     * Formats the time left, e.g. `01:23` or `1:02:03`.
     *
     * Returns `--` when the total size or the rate is unknown, which is the honest answer
     * for a chunked upload whose length the client never declared.
     */
    fun formatEta(transferred: Long, total: Long, bytesPerSecond: Long): String {
        if (total <= 0 || bytesPerSecond <= 0 || transferred >= total) return "--"
        val seconds = (total - transferred) / bytesPerSecond
        return formatDuration(seconds)
    }

    fun formatDuration(totalSeconds: Long): String {
        if (totalSeconds < 0) return "--"
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    /** Percentage of [total] transferred, or -1 when the total is unknown. */
    fun percentOf(transferred: Long, total: Long): Int =
        if (total <= 0) -1 else ((transferred * 100) / total).coerceIn(0, 100).toInt()
}
