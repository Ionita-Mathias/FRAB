package ch.genedis.tvfileserver.core.ftp

import ch.genedis.tvfileserver.core.vfs.VfsEntry
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formats directory entries for the FTP data channel.
 *
 * `LIST` has no standard format; virtually every client parses the Unix `ls -l` layout, so
 * that is what is emitted, in [Locale.ENGLISH] so a French device locale does not produce month
 * names no client understands. `MLSD` is the machine-readable alternative and is preferred
 * by modern clients when the server advertises it.
 */
object FtpListFormatter {

    private val RECENT_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM ppd HH:mm", Locale.ENGLISH).withZone(ZoneOffset.UTC)

    private val OLD_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM ppd  yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC)

    private val MLSD_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ENGLISH).withZone(ZoneOffset.UTC)

    private const val SIX_MONTHS_MILLIS = 180L * 24 * 60 * 60 * 1000

    /** One `ls -l` style line, without its trailing CRLF. */
    fun listLine(entry: VfsEntry, now: Long = System.currentTimeMillis()): String {
        val permissions = buildString {
            append(if (entry.isDirectory) 'd' else '-')
            append(if (entry.readable) 'r' else '-')
            append(if (entry.writable) 'w' else '-')
            append(if (entry.isDirectory) 'x' else '-')
            append("r-")
            append(if (entry.isDirectory) 'x' else '-')
            append("r-")
            append(if (entry.isDirectory) 'x' else '-')
        }
        val links = if (entry.isDirectory) 2 else 1
        val size = entry.size
        val date = formatDate(entry.lastModified, now)
        return String.format(
            Locale.ENGLISH,
            "%s %3d %-8s %-8s %12d %s %s",
            permissions,
            links,
            "ftp",
            "ftp",
            size,
            date,
            entry.name,
        )
    }

    /** One `MLSD` fact line, without its trailing CRLF. */
    fun mlsdLine(entry: VfsEntry, writable: Boolean): String {
        val type = if (entry.isDirectory) "dir" else "file"
        val perm = permissionFacts(entry, writable)
        val builder = StringBuilder(96)
        builder.append("type=").append(type).append(';')
        if (!entry.isDirectory) builder.append("size=").append(entry.size).append(';')
        builder.append("modify=").append(MLSD_TIME.format(Instant.ofEpochMilli(entry.lastModified))).append(';')
        builder.append("perm=").append(perm).append(';')
        builder.append(' ').append(entry.name)
        return builder.toString()
    }

    /** The `MLST` variant, which uses the full path as its name. */
    fun mlstLine(entry: VfsEntry, writable: Boolean, displayPath: String): String {
        val base = mlsdLine(entry, writable)
        val cut = base.lastIndexOf("; ")
        return if (cut < 0) base else base.substring(0, cut + 2) + displayPath
    }

    fun formatModifyTime(epochMillis: Long): String =
        MLSD_TIME.format(Instant.ofEpochMilli(epochMillis))

    /**
     * RFC 3659 permission facts.
     *
     * `e` enter, `l` list, `c` create files, `m` create directories, `d` delete,
     * `f` rename, `p` purge, `r` retrieve, `w` store, `a` append.
     */
    private fun permissionFacts(entry: VfsEntry, writable: Boolean): String = buildString {
        if (entry.isDirectory) {
            append("el")
            if (writable && entry.writable) append("cmdfp")
        } else {
            if (entry.readable) append('r')
            if (writable && entry.writable) append("adfw")
        }
    }

    private fun formatDate(lastModified: Long, now: Long): String {
        val instant = Instant.ofEpochMilli(lastModified)
        val age = now - lastModified
        return if (age in 0..SIX_MONTHS_MILLIS) {
            RECENT_DATE.format(instant)
        } else {
            OLD_DATE.format(instant)
        }
    }
}
