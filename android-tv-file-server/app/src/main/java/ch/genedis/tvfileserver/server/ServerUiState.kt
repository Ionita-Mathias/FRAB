package ch.genedis.tvfileserver.server

import ch.genedis.tvfileserver.core.transfer.TransferInfo
import ch.genedis.tvfileserver.core.transfer.TransferTotals
import ch.genedis.tvfileserver.core.vfs.VfsRoot
import ch.genedis.tvfileserver.net.NetworkAddress

enum class ServerStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

/**
 * A storage root plus its space figures.
 *
 * The numbers are gathered on the IO dispatcher when the state is built, so the UI never
 * calls `File.usableSpace` — a stat syscall that can stall on a sleeping USB drive — on the
 * main thread.
 */
data class StorageSummary(
    val root: VfsRoot,
    val freeBytes: Long,
    val totalBytes: Long,
)

/**
 * Everything the TV screen and the notification need, in one immutable snapshot.
 *
 * Derived URLs are computed here so the UI never has to assemble them itself.
 */
data class ServerUiState(
    val status: ServerStatus = ServerStatus.STOPPED,
    val httpPort: Int = 8080,
    val ftpPort: Int = 2121,
    val ftpEnabled: Boolean = true,
    val webdavEnabled: Boolean = true,
    val webdavMount: String = "/dav",
    val addresses: List<NetworkAddress> = emptyList(),
    val primaryAddress: String? = null,
    val username: String = "tv",
    val password: String = "",
    val authEnabled: Boolean = true,
    val readOnly: Boolean = false,
    val roots: List<StorageSummary> = emptyList(),
    val transfers: List<TransferInfo> = emptyList(),
    val totals: TransferTotals = TransferTotals(0, 0, 0, 0, 0),
    val errorMessage: String? = null,
    val hasStoragePermission: Boolean = false,
    val autoLoginToken: String? = null,
    val deviceName: String = "Android TV",
) {

    val isRunning: Boolean get() = status == ServerStatus.RUNNING

    val isBusy: Boolean get() = status == ServerStatus.STARTING || status == ServerStatus.STOPPING

    /** The browser URL, or null while no address is known. */
    val webUrl: String? get() = primaryAddress?.let { "http://$it:$httpPort" }

    /** The URL to paste into Finder's "Connect to Server". */
    val davUrl: String? get() = if (webdavEnabled) webUrl?.plus(webdavMount) else null

    val ftpUrl: String? get() = if (ftpEnabled && ftpPort > 0) primaryAddress?.let { "ftp://$it:$ftpPort" } else null

    /**
     * What the QR code encodes.
     *
     * With authentication on it carries the one-shot login token so scanning the code from
     * a phone opens the UI already signed in.
     */
    val qrPayload: String?
        get() {
            val base = webUrl ?: return null
            val token = autoLoginToken
            return if (authEnabled && !token.isNullOrEmpty()) "$base/?k=$token" else base
        }
}
