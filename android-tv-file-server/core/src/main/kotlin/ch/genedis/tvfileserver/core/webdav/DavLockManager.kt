package ch.genedis.tvfileserver.core.webdav

import ch.genedis.tvfileserver.core.vfs.VPath
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A WebDAV write lock. */
class DavLock(
    val token: String,
    val path: VPath,
    val depth: Int,
    val owner: String,
    val exclusive: Boolean,
    @Volatile var expiresAt: Long,
) {
    val isExpired: Boolean get() = System.currentTimeMillis() >= expiresAt
}

/**
 * In-memory lock table.
 *
 * macOS Finder refuses to mount a WebDAV share read-write unless the server advertises
 * `DAV: 2` and honours LOCK/UNLOCK, so this is not optional. Locks live only as long as the
 * process: a lock surviving a reboot would strand files no client remembers holding.
 */
class DavLockManager(private val defaultTimeoutSeconds: Long = 3600) {

    private val locksByToken = ConcurrentHashMap<String, DavLock>()

    fun lock(
        path: VPath,
        depth: Int,
        owner: String,
        exclusive: Boolean,
        timeoutSeconds: Long?,
    ): DavLock {
        purgeExpired()
        val seconds = (timeoutSeconds ?: defaultTimeoutSeconds).coerceIn(1, MAX_TIMEOUT_SECONDS)
        val lock = DavLock(
            token = "opaquelocktoken:" + UUID.randomUUID(),
            path = path,
            depth = depth,
            owner = owner,
            exclusive = exclusive,
            expiresAt = System.currentTimeMillis() + seconds * 1000,
        )
        locksByToken[lock.token] = lock
        return lock
    }

    /** Extends an existing lock; returns null when the token is unknown or expired. */
    fun refresh(token: String, timeoutSeconds: Long?): DavLock? {
        purgeExpired()
        val lock = locksByToken[token] ?: return null
        val seconds = (timeoutSeconds ?: defaultTimeoutSeconds).coerceIn(1, MAX_TIMEOUT_SECONDS)
        lock.expiresAt = System.currentTimeMillis() + seconds * 1000
        return lock
    }

    fun unlock(token: String): Boolean {
        purgeExpired()
        return locksByToken.remove(token) != null
    }

    /** The lock covering [path], either directly or through a depth-infinity ancestor. */
    fun find(path: VPath): DavLock? {
        purgeExpired()
        return locksByToken.values.firstOrNull { lock ->
            lock.path == path || (lock.depth != 0 && path.startsWith(lock.path))
        }
    }

    /** True when [path] is locked by someone who did not present a matching token. */
    fun isLockedForOthers(path: VPath, tokens: Collection<String>): Boolean {
        val lock = find(path) ?: return false
        return tokens.none { presented -> normalizeToken(presented) == lock.token }
    }

    fun purgeExpired() {
        locksByToken.entries.removeIf { it.value.isExpired }
    }

    /** Timeout header value for a lock response, e.g. `Second-3600`. */
    fun timeoutHeader(lock: DavLock): String {
        val remaining = ((lock.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        return "Second-$remaining"
    }

    companion object {
        const val MAX_TIMEOUT_SECONDS = 24 * 3600L

        /** Strips the angle brackets clients wrap around a token in the `If` header. */
        fun normalizeToken(raw: String): String = raw.trim().removePrefix("<").removeSuffix(">")

        /**
         * Parses a `Timeout: Infinite, Second-600` header into seconds.
         *
         * The header is a preference list. A finite value always wins over `Infinite`: an
         * abandoned client should not be able to hold a lock for a whole day.
         */
        fun parseTimeout(header: String?): Long? {
            if (header == null) return null
            var infinite = false
            for (candidate in header.split(',')) {
                val value = candidate.trim()
                if (value.startsWith("Second-", ignoreCase = true)) {
                    value.substring(7).trim().toLongOrNull()?.let { return it }
                }
                if (value.equals("Infinite", ignoreCase = true)) infinite = true
            }
            return if (infinite) MAX_TIMEOUT_SECONDS else null
        }

        /**
         * Extracts every lock token mentioned in an `If` header.
         *
         * The full `If` grammar supports tagged lists and entity tags; a file server only
         * needs the tokens, so anything between angle brackets that looks like a lock token
         * is collected and the rest is ignored.
         */
        fun parseIfTokens(header: String?): List<String> {
            if (header.isNullOrEmpty()) return emptyList()
            val tokens = ArrayList<String>(2)
            var index = 0
            while (index < header.length) {
                val open = header.indexOf('(', index)
                if (open < 0) break
                val close = header.indexOf(')', open)
                if (close < 0) break
                val group = header.substring(open + 1, close)
                var cursor = 0
                while (cursor < group.length) {
                    val start = group.indexOf('<', cursor)
                    if (start < 0) break
                    val end = group.indexOf('>', start)
                    if (end < 0) break
                    val token = group.substring(start + 1, end)
                    if (token.startsWith("opaquelocktoken:") || token.startsWith("urn:uuid:")) {
                        tokens.add(token)
                    }
                    cursor = end + 1
                }
                index = close + 1
            }
            return tokens
        }
    }
}
