package ch.genedis.tvfileserver.core.auth

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Opaque bearer tokens for the web UI, so the browser does not have to replay Basic
 * credentials on every request (and so a shoulder-surfer at the TV cannot read them back
 * out of the address bar).
 */
class SessionStore(
    private val ttlMillis: Long = 12 * 60 * 60_000L,
    private val maxSessions: Int = 32,
) {

    private class Session(val username: String, @Volatile var expiresAt: Long)

    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Session>()

    @Volatile
    private var autoLogin: String? = null

    /**
     * A long-lived token embedded in the QR code so scanning the TV screen with a phone
     * logs straight in. Rotated by [invalidateAll], i.e. whenever the password changes.
     */
    val autoLoginToken: String
        get() = autoLogin ?: synchronized(this) {
            autoLogin ?: newToken().also { autoLogin = it }
        }

    fun create(username: String): String {
        purge()
        if (sessions.size >= maxSessions) {
            // Evict whatever expires first rather than refusing the login.
            sessions.entries.minByOrNull { it.value.expiresAt }?.let { sessions.remove(it.key) }
        }
        val token = newToken()
        sessions[token] = Session(username, System.currentTimeMillis() + ttlMillis)
        return token
    }

    /** Returns the session's username and slides its expiry forward, or null when invalid. */
    fun validate(token: String?): String? {
        if (token.isNullOrEmpty()) return null
        val session = sessions[token] ?: return null
        val now = System.currentTimeMillis()
        if (session.expiresAt <= now) {
            sessions.remove(token)
            return null
        }
        session.expiresAt = now + ttlMillis
        return session.username
    }

    fun invalidate(token: String?) {
        if (token != null) sessions.remove(token)
    }

    /** Drops every session and rotates the auto-login token. */
    fun invalidateAll() {
        sessions.clear()
        synchronized(this) { autoLogin = newToken() }
    }

    private fun purge() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { it.value.expiresAt <= now }
    }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
