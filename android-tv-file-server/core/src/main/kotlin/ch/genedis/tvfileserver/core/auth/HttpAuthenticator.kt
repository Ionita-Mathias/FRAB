package ch.genedis.tvfileserver.core.auth

import ch.genedis.tvfileserver.core.http.HttpHeaderNames
import ch.genedis.tvfileserver.core.http.HttpRequest
import ch.genedis.tvfileserver.core.http.HttpResponse
import ch.genedis.tvfileserver.core.http.HttpStatus
import ch.genedis.tvfileserver.core.util.CoreLog
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * Authenticates HTTP, WebDAV and FTP callers against a single [AuthPolicy].
 *
 * Three credential carriers are accepted, in this order: the web UI's session cookie, a
 * bearer token (also used by the QR auto-login link), and HTTP Basic — which is what macOS
 * Finder, Windows Explorer, rclone and curl speak.
 */
class HttpAuthenticator(
    @Volatile var policy: AuthPolicy,
    val sessions: SessionStore = SessionStore(),
    private val throttler: LoginThrottler = LoginThrottler(),
) {

    /** Resolves the caller's identity from whichever credential carrier they used. */
    fun authenticate(request: HttpRequest): AuthResult {
        if (!policy.enabled) {
            return AuthResult(authenticated = true, username = ANONYMOUS, readOnly = false)
        }

        sessions.validate(request.cookie(SESSION_COOKIE))?.let { username ->
            return AuthResult(authenticated = true, username = username, readOnly = false)
        }

        val authorization = request.header(HttpHeaderNames.AUTHORIZATION)?.trim()
        if (authorization != null) {
            val space = authorization.indexOf(' ')
            val scheme = (if (space < 0) authorization else authorization.substring(0, space))
                .lowercase(Locale.ROOT)
            val token = if (space < 0) "" else authorization.substring(space + 1).trim()
            when (scheme) {
                "bearer" -> {
                    sessions.validate(token)?.let { username ->
                        return AuthResult(authenticated = true, username = username, readOnly = false)
                    }
                    if (token.isNotEmpty() && constantTimeEquals(token, sessions.autoLoginToken)) {
                        return AuthResult(true, policy.credentials.username, readOnly = false)
                    }
                }
                "basic" -> {
                    val decoded = decodeBasic(token)
                    if (decoded != null && matches(decoded.first, decoded.second)) {
                        throttler.recordSuccess(request.remoteAddress)
                        return AuthResult(true, decoded.first, readOnly = false)
                    }
                    throttler.recordFailure(request.remoteAddress)
                    CoreLog.w(TAG, "Rejected Basic credentials from ${request.remoteAddress}")
                }
            }
        }

        return if (policy.allowAnonymousRead) {
            AuthResult(authenticated = true, username = ANONYMOUS, readOnly = true)
        } else {
            AuthResult.ANONYMOUS_DENIED
        }
    }

    /** Credential check for the FTP control channel. */
    fun checkFtp(username: String, password: String, remote: String): Boolean {
        if (!policy.enabled) return true
        if (throttler.isBlocked(remote)) {
            CoreLog.w(TAG, "FTP login from $remote is throttled")
            return false
        }
        if (matches(username, password)) {
            throttler.recordSuccess(remote)
            return true
        }
        if (policy.allowAnonymousRead && username.lowercase(Locale.ROOT) in ANONYMOUS_NAMES) {
            return true
        }
        throttler.recordFailure(remote)
        return false
    }

    /** True when the FTP session that authenticated as [username] may only read. */
    fun isFtpReadOnly(username: String): Boolean =
        policy.enabled && username.lowercase(Locale.ROOT) in ANONYMOUS_NAMES &&
            !constantTimeEquals(username, policy.credentials.username)

    /** The `401` a non-browser client needs in order to retry with credentials. */
    fun challenge(): HttpResponse =
        HttpResponse.error(HttpStatus.UNAUTHORIZED, "Authentication required")
            .header(HttpHeaderNames.WWW_AUTHENTICATE, "Basic realm=\"${policy.realm}\", charset=\"UTF-8\"")

    /**
     * Verifies credentials submitted through the web login form.
     *
     * @return a fresh session token, or null when the credentials are wrong or the caller is
     *   currently throttled.
     */
    fun login(request: HttpRequest, username: String, password: String): String? {
        val key = request.remoteAddress
        if (throttler.isBlocked(key)) {
            CoreLog.w(TAG, "Login from $key is throttled")
            return null
        }
        if (!policy.enabled || matches(username, password)) {
            throttler.recordSuccess(key)
            return sessions.create(if (policy.enabled) username else ANONYMOUS)
        }
        throttler.recordFailure(key)
        return null
    }

    fun isThrottled(remoteAddress: String): Boolean = throttler.isBlocked(remoteAddress)

    fun retryAfterSeconds(remoteAddress: String): Long = throttler.retryAfterSeconds(remoteAddress)

    fun setSessionCookie(response: HttpResponse, token: String): HttpResponse =
        response.addHeader(
            HttpHeaderNames.SET_COOKIE,
            "$SESSION_COOKIE=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=$COOKIE_MAX_AGE_SECONDS",
        )

    fun clearSessionCookie(response: HttpResponse): HttpResponse =
        response.addHeader(
            HttpHeaderNames.SET_COOKIE,
            "$SESSION_COOKIE=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0",
        )

    private fun matches(username: String, password: String): Boolean {
        val expected = policy.credentials
        // Compare both fields unconditionally so the response time does not reveal whether
        // the username alone was right.
        val userOk = constantTimeEquals(username, expected.username)
        val passOk = constantTimeEquals(password, expected.password)
        return userOk && passOk
    }

    private fun decodeBasic(token: String): Pair<String, String>? = try {
        val decoded = String(Base64.getDecoder().decode(token), Charsets.UTF_8)
        val colon = decoded.indexOf(':')
        if (colon < 0) null else decoded.substring(0, colon) to decoded.substring(colon + 1)
    } catch (error: IllegalArgumentException) {
        CoreLog.d(TAG, "Malformed Basic credentials: ${error.message}")
        null
    }

    companion object {
        const val SESSION_COOKIE = "tvfs_session"
        const val ANONYMOUS = "anonymous"
        private const val TAG = "HttpAuthenticator"
        private const val COOKIE_MAX_AGE_SECONDS = 12 * 60 * 60
        private val ANONYMOUS_NAMES = setOf("anonymous", "ftp", "guest")

        /** Length-independent comparison, so timing cannot be used to recover the password. */
        fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
