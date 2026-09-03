package ch.genedis.tvfileserver.core.config

import ch.genedis.tvfileserver.core.auth.AuthPolicy
import ch.genedis.tvfileserver.core.auth.Credentials
import ch.genedis.tvfileserver.core.util.DEFAULT_BUFFER_BYTES

/**
 * Everything the protocol stack needs to know, in one immutable value.
 *
 * The Android layer maps its persisted preferences onto this so the core module never has to
 * know about DataStore.
 */
data class CoreConfig(
    val httpPort: Int = DEFAULT_HTTP_PORT,
    val ftpPort: Int = DEFAULT_FTP_PORT,
    val httpEnabled: Boolean = true,
    val ftpEnabled: Boolean = true,
    val webdavEnabled: Boolean = true,
    val webdavMount: String = DEFAULT_WEBDAV_MOUNT,
    val authEnabled: Boolean = true,
    val username: String = "tv",
    val password: String = "",
    val allowAnonymousRead: Boolean = false,
    val readOnly: Boolean = false,
    val hideDotFiles: Boolean = true,
    val maxHttpConnections: Int = 24,
    val maxFtpSessions: Int = 8,
    val passivePortStart: Int = 2130,
    val passivePortEnd: Int = 2160,
    val bufferSize: Int = DEFAULT_BUFFER_BYTES,
    val serverName: String = "TV File Server",
    val appVersion: String = "1.0.0",
) {

    fun authPolicy(): AuthPolicy = AuthPolicy(
        enabled = authEnabled,
        credentials = Credentials(username, password),
        allowAnonymousRead = allowAnonymousRead,
        realm = serverName,
    )

    /**
     * Clamps every field into a range the server can actually honour, so a corrupted
     * preference file can never leave the service unable to start.
     */
    fun validated(): CoreConfig {
        val http = if (httpPort in 1..65535) httpPort else DEFAULT_HTTP_PORT
        val ftp = if (ftpPort in 1..65535) ftpPort else DEFAULT_FTP_PORT
        val passiveStart = passivePortStart.coerceIn(1024, 65535)
        val passiveEnd = passivePortEnd.coerceIn(passiveStart, 65535)
        val mount = webdavMount.trim().let { raw ->
            val withSlash = if (raw.startsWith("/")) raw else "/$raw"
            val trimmed = withSlash.trimEnd('/')
            if (trimmed.length <= 1) DEFAULT_WEBDAV_MOUNT else trimmed
        }
        return copy(
            httpPort = http,
            ftpPort = if (ftp == http) DEFAULT_FTP_PORT else ftp,
            webdavMount = mount,
            maxHttpConnections = maxHttpConnections.coerceIn(4, 128),
            maxFtpSessions = maxFtpSessions.coerceIn(1, 32),
            passivePortStart = passiveStart,
            passivePortEnd = passiveEnd,
            bufferSize = bufferSize.coerceIn(8 * 1024, 1024 * 1024),
            username = username.ifBlank { "tv" },
        )
    }

    companion object {
        const val DEFAULT_HTTP_PORT = 8080
        const val DEFAULT_FTP_PORT = 2121
        const val DEFAULT_WEBDAV_MOUNT = "/dav"
    }
}
