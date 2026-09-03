package ch.genedis.tvfileserver.core.vfs

/**
 * An absolute, always-normalised path inside the server's virtual namespace.
 *
 * The namespace has a synthetic root (`/`) whose children are the storage roots, e.g.
 * `/internal/Movies/film.mkv`. Instances are immutable and can never point outside the
 * namespace: normalisation happens once, in [of], and `..` can never pop past the root.
 */
class VPath private constructor(val value: String) : Comparable<VPath> {

    val isRoot: Boolean get() = value == "/"

    /** Last path segment, or the empty string for the root. */
    val name: String get() = if (isRoot) "" else value.substringAfterLast('/')

    /** Parent path, or null for the root. */
    val parent: VPath?
        get() {
            if (isRoot) return null
            val cut = value.lastIndexOf('/')
            return if (cut <= 0) ROOT else VPath(value.substring(0, cut))
        }

    /** The individual segments, empty for the root. */
    val segments: List<String>
        get() = if (isRoot) emptyList() else value.substring(1).split('/')

    /** The id of the storage root this path lives in, or null for the virtual root. */
    val rootId: String? get() = if (isRoot) null else value.substring(1).substringBefore('/')

    /** Appends a single already-validated segment. */
    fun child(name: String): VPath {
        require(isValidSegment(name)) { "Invalid path segment: '$name'" }
        return VPath(if (isRoot) "/$name" else "$value/$name")
    }

    /** Resolves [relative] against this path, normalising the result. */
    fun resolve(relative: String): VPath =
        if (relative.startsWith("/")) of(relative) else of("$value/$relative")

    /** True when this path equals [other] or is nested inside it. */
    fun startsWith(other: VPath): Boolean =
        value == other.value || other.isRoot || value.startsWith(other.value + "/")

    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = this === other || (other is VPath && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    override fun compareTo(other: VPath): Int = value.compareTo(other.value)

    companion object {

        val ROOT: VPath = VPath("/")

        /**
         * Normalises [raw] into a virtual path.
         *
         * Never throws: empty, `.` and invalid segments are dropped, `..` pops one level and
         * is ignored at the root, and repeated separators collapse. Backslashes are treated
         * as separators so Windows clients behave.
         */
        fun of(raw: String): VPath {
            if (raw.isEmpty() || raw == "/") return ROOT
            val segments = ArrayList<String>(8)
            var start = 0
            val text = raw
            while (start <= text.length) {
                var end = start
                while (end < text.length && text[end] != '/' && text[end] != '\\') end++
                val segment = text.substring(start, end)
                when {
                    segment.isEmpty() || segment == "." -> Unit
                    segment == ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                    else -> {
                        val cleaned = sanitizeSegment(segment)
                        if (cleaned.isNotEmpty() && cleaned != "." && cleaned != "..") segments.add(cleaned)
                    }
                }
                if (end >= text.length) break
                start = end + 1
            }
            return if (segments.isEmpty()) ROOT else VPath("/" + segments.joinToString("/"))
        }

        /** Like [of], but returns null when any segment was structurally invalid. */
        fun ofOrNull(raw: String): VPath? {
            for (segment in raw.split('/', '\\')) {
                if (segment.isEmpty() || segment == "." || segment == "..") continue
                if (!isValidSegment(segment)) return null
            }
            return of(raw)
        }

        /** True when [name] is usable as a single path segment. */
        fun isValidSegment(name: String): Boolean {
            if (name.isEmpty() || name == "." || name == "..") return false
            for (ch in name) {
                if (ch == '/' || ch == '\\' || ch.code == 0) return false
                // Reject the remaining C0 controls: they cannot appear in a real file name and
                // are a classic way to smuggle separators past naive checks.
                if (ch.code < 0x20) return false
            }
            // A trailing dot or space is silently dropped by some filesystems, which would make
            // the resolved path differ from the requested one.
            return name.last() != ' ' && name != " "
        }

        private fun sanitizeSegment(segment: String): String {
            val builder = StringBuilder(segment.length)
            for (ch in segment) {
                if (ch.code == 0 || ch.code < 0x20) continue
                builder.append(ch)
            }
            return builder.toString().trimEnd(' ')
        }
    }
}
