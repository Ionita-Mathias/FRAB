package ch.genedis.tvfileserver.core

import ch.genedis.tvfileserver.core.auth.HttpAuthenticator
import ch.genedis.tvfileserver.core.config.CoreConfig
import ch.genedis.tvfileserver.core.ftp.FtpConfig
import ch.genedis.tvfileserver.core.ftp.FtpServer
import ch.genedis.tvfileserver.core.http.HttpServer
import ch.genedis.tvfileserver.core.http.HttpServerConfig
import ch.genedis.tvfileserver.core.http.Router
import ch.genedis.tvfileserver.core.transfer.TransferRegistry
import ch.genedis.tvfileserver.core.util.CoreLog
import ch.genedis.tvfileserver.core.vfs.VirtualFileSystem
import ch.genedis.tvfileserver.core.web.ServerInfo
import ch.genedis.tvfileserver.core.web.StaticAssetSource
import ch.genedis.tvfileserver.core.web.WebInterfaceHandler
import ch.genedis.tvfileserver.core.webdav.DavLockManager
import ch.genedis.tvfileserver.core.webdav.WebDavHandler
import kotlinx.coroutines.CoroutineScope
import java.io.IOException

/** Ports the server actually bound. `-1` means the protocol is off or failed to bind. */
data class CoreStartResult(val httpPort: Int, val ftpPort: Int)

/**
 * Wires the protocol stack together and owns its lifecycle.
 *
 * Keeping this in the core module means the Android service is a thin lifecycle shell, and
 * the whole server can be started inside a JVM test.
 */
class FileServerCore(
    private val vfsProvider: () -> VirtualFileSystem,
    private val assets: StaticAssetSource,
    private val infoProvider: () -> ServerInfo,
    initialConfig: CoreConfig,
) {

    val transfers = TransferRegistry()

    val authenticator = HttpAuthenticator(initialConfig.validated().authPolicy())

    @Volatile
    var config: CoreConfig = initialConfig.validated()
        private set

    private val locks = DavLockManager()

    @Volatile
    private var httpServer: HttpServer? = null

    @Volatile
    private var ftpServer: FtpServer? = null

    @Volatile
    private var activeVfs: VirtualFileSystem? = null

    val isRunning: Boolean get() = httpServer?.isRunning == true || ftpServer?.isRunning == true

    /** The filesystem the running server is serving, or null when stopped. */
    val filesystem: VirtualFileSystem? get() = activeVfs

    /**
     * Binds every enabled listener synchronously and starts their accept loops in [scope].
     *
     * @throws IOException when the HTTP port is taken. An FTP bind failure is reported as
     *   `ftpPort = -1` instead of tearing the server down: losing FTP is an inconvenience,
     *   losing the web UI would leave the user with no way to fix the port from a phone.
     */
    fun start(scope: CoroutineScope): CoreStartResult {
        check(!isRunning) { "Server already running" }
        val current = config
        val vfs = vfsProvider()
        activeVfs = vfs

        val router = Router()
        if (current.webdavEnabled) {
            router.mount(
                current.webdavMount,
                WebDavHandler(vfs, authenticator, transfers, locks) { config },
            )
        }
        val web = WebInterfaceHandler(vfs, authenticator, assets, transfers, { config }, infoProvider)
        router.notFound(web.asHandler())

        var httpPort = -1
        if (current.httpEnabled) {
            val server = HttpServer(
                HttpServerConfig(
                    port = current.httpPort,
                    maxConnections = current.maxHttpConnections,
                    bufferSize = current.bufferSize,
                    serverHeader = "TvFileServer/${current.appVersion}",
                ),
                router.asHandler(),
            )
            server.start(scope)
            httpServer = server
            httpPort = server.boundPort
        }

        var ftpPort = -1
        if (current.ftpEnabled) {
            val server = FtpServer(
                FtpConfig(
                    port = current.ftpPort,
                    maxSessions = current.maxFtpSessions,
                    passivePortStart = current.passivePortStart,
                    passivePortEnd = current.passivePortEnd,
                    bufferSize = current.bufferSize,
                    welcomeMessage = "${current.serverName} ready",
                ),
                vfs,
                authenticator,
                transfers,
            )
            try {
                server.start(scope)
                ftpServer = server
                ftpPort = server.boundPort
            } catch (error: IOException) {
                CoreLog.e(TAG, "FTP could not bind port ${current.ftpPort}", error)
                if (!current.httpEnabled) {
                    stop()
                    throw error
                }
            }
        }

        CoreLog.i(TAG, "Server started (http=$httpPort, ftp=$ftpPort)")
        return CoreStartResult(httpPort, ftpPort)
    }

    /** Stops every listener. Never throws, so it is safe on a service teardown path. */
    fun stop() {
        try {
            httpServer?.stop()
        } catch (error: Exception) {
            CoreLog.w(TAG, "Cannot stop the HTTP server", error)
        }
        try {
            ftpServer?.stop()
        } catch (error: Exception) {
            CoreLog.w(TAG, "Cannot stop the FTP server", error)
        }
        httpServer = null
        ftpServer = null
        activeVfs = null
        locks.purgeExpired()
    }

    /**
     * Applies settings that do not need a rebind (credentials, visibility, read-only).
     *
     * Call [requiresRestart] first: anything it flags needs a stop/start cycle.
     */
    fun updateConfig(newConfig: CoreConfig) {
        val validated = newConfig.validated()
        val credentialsChanged = validated.username != config.username ||
            validated.password != config.password ||
            validated.authEnabled != config.authEnabled
        config = validated
        authenticator.policy = validated.authPolicy()
        if (credentialsChanged) {
            // Old cookies and the QR token must stop working the moment the password changes.
            authenticator.sessions.invalidateAll()
        }
    }

    /** True when moving from [old] to [new] requires rebinding sockets. */
    fun requiresRestart(old: CoreConfig, new: CoreConfig): Boolean {
        val a = old.validated()
        val b = new.validated()
        return a.httpPort != b.httpPort ||
            a.ftpPort != b.ftpPort ||
            a.httpEnabled != b.httpEnabled ||
            a.ftpEnabled != b.ftpEnabled ||
            a.webdavEnabled != b.webdavEnabled ||
            a.webdavMount != b.webdavMount ||
            a.maxHttpConnections != b.maxHttpConnections ||
            a.maxFtpSessions != b.maxFtpSessions ||
            a.passivePortStart != b.passivePortStart ||
            a.passivePortEnd != b.passivePortEnd ||
            a.bufferSize != b.bufferSize ||
            a.readOnly != b.readOnly ||
            a.hideDotFiles != b.hideDotFiles
    }

    private companion object {
        const val TAG = "FileServerCore"
    }
}
