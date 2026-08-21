package ch.genedis.tvfileserver.core.web

/** Everything the web UI needs to describe the server it is talking to. */
data class ServerInfo(
    val serverName: String,
    val appVersion: String,
    val httpPort: Int,
    val ftpPort: Int,
    val ftpEnabled: Boolean,
    val webdavEnabled: Boolean,
    val webdavMount: String,
    /** Bare IPv4 addresses the server is reachable on. */
    val addresses: List<String>,
    val readOnly: Boolean,
    val authEnabled: Boolean,
    val deviceName: String,
)
