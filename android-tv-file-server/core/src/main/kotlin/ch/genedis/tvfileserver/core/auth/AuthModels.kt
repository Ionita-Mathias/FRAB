package ch.genedis.tvfileserver.core.auth

/** A username/password pair. */
data class Credentials(val username: String, val password: String)

/**
 * How the server treats unauthenticated callers.
 *
 * @param enabled when false the server is wide open on the LAN — only sensible on a trusted
 *   network, and the TV UI warns about it.
 * @param allowAnonymousRead lets unauthenticated callers browse and download, but never write.
 */
data class AuthPolicy(
    val enabled: Boolean,
    val credentials: Credentials,
    val allowAnonymousRead: Boolean,
    val realm: String = "TV File Server",
)

/** Outcome of authenticating one request or FTP login. */
data class AuthResult(
    val authenticated: Boolean,
    val username: String?,
    val readOnly: Boolean,
) {
    companion object {
        val ANONYMOUS_DENIED = AuthResult(authenticated = false, username = null, readOnly = true)
    }
}
