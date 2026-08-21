package ch.genedis.tvfileserver.core.ftp

import ch.genedis.tvfileserver.core.util.DEFAULT_BUFFER_BYTES
import java.net.InetAddress

/**
 * FTP server tuning.
 *
 * The passive port range matters: clients behind a firewall need a small, predictable set of
 * data ports to open, and picking a random ephemeral port would make the server unusable
 * through most home routers.
 */
data class FtpConfig(
    val port: Int = 2121,
    val bindAddress: InetAddress? = null,
    val maxSessions: Int = 8,
    val passivePortStart: Int = 2130,
    val passivePortEnd: Int = 2160,
    val controlTimeoutMs: Int = 5 * 60_000,
    val dataTimeoutMs: Int = 60_000,
    val bufferSize: Int = DEFAULT_BUFFER_BYTES,
    val welcomeMessage: String = "TV File Server ready",
    val allowActiveMode: Boolean = true,
    val backlog: Int = 16,
)
