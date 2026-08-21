package ch.genedis.tvfileserver.core.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-client login throttle.
 *
 * A LAN file server is a plausible target for a password guesser on a compromised device, and
 * the generated passwords are short enough to be worth protecting. A fixed window keyed by
 * remote address is enough here and costs nothing when nobody is failing.
 */
class LoginThrottler(
    private val maxFailures: Int = 10,
    private val windowMillis: Long = 60_000L,
) {

    private class Window(@Volatile var failures: Int, @Volatile var startedAt: Long)

    private val windows = ConcurrentHashMap<String, Window>()

    fun isBlocked(key: String): Boolean {
        val window = windows[key] ?: return false
        if (expired(window)) {
            windows.remove(key)
            return false
        }
        return window.failures >= maxFailures
    }

    fun recordFailure(key: String) {
        val now = System.currentTimeMillis()
        val window = windows.computeIfAbsent(key) { Window(0, now) }
        synchronized(window) {
            if (now - window.startedAt > windowMillis) {
                window.startedAt = now
                window.failures = 0
            }
            window.failures++
        }
        // Opportunistic cleanup so a scanner walking the subnet cannot grow the map forever.
        if (windows.size > MAX_TRACKED_CLIENTS) {
            windows.entries.removeIf { expired(it.value) }
        }
    }

    fun recordSuccess(key: String) {
        windows.remove(key)
    }

    /** Seconds the caller should wait before retrying, for the `Retry-After` header. */
    fun retryAfterSeconds(key: String): Long {
        val window = windows[key] ?: return 0
        val remaining = windowMillis - (System.currentTimeMillis() - window.startedAt)
        return if (remaining <= 0) 0 else (remaining + 999) / 1000
    }

    private fun expired(window: Window): Boolean =
        System.currentTimeMillis() - window.startedAt > windowMillis

    private companion object {
        const val MAX_TRACKED_CLIENTS = 512
    }
}
