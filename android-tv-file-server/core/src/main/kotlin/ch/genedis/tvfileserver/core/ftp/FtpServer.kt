package ch.genedis.tvfileserver.core.ftp

import ch.genedis.tvfileserver.core.auth.HttpAuthenticator
import ch.genedis.tvfileserver.core.transfer.TransferRegistry
import ch.genedis.tvfileserver.core.util.CoreLog
import ch.genedis.tvfileserver.core.util.closeQuietly
import ch.genedis.tvfileserver.core.vfs.VirtualFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * A plain FTP server (no FTPS).
 *
 * FTP is kept alongside WebDAV because it is what media clients speak: Kodi, VLC, most
 * network-aware TV apps, FileZilla, Cyberduck and rclone all mount it without extra
 * software. Credentials travel in the clear, so the UI states plainly that this is for a
 * trusted LAN.
 */
class FtpServer(
    private val config: FtpConfig,
    private val vfs: VirtualFileSystem,
    private val auth: HttpAuthenticator,
    private val transfers: TransferRegistry,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sessionDispatcher = Dispatchers.IO.limitedParallelism(config.maxSessions + 2)

    private val sessions: MutableSet<FtpSession> =
        Collections.newSetFromMap(ConcurrentHashMap<FtpSession, Boolean>())

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    private var stopped = false

    @Volatile
    var boundPort: Int = -1
        private set

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    val sessionCount: Int get() = sessions.size

    /**
     * Binds the control port synchronously, then accepts in [scope].
     *
     * @throws IOException when the port cannot be bound.
     */
    fun start(scope: CoroutineScope) {
        check(serverSocket == null) { "FTP server already started" }
        stopped = false
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(config.bindAddress, config.port), config.backlog)
        } catch (error: IOException) {
            closeQuietly(socket)
            throw IOException("Cannot bind FTP port ${config.port}: ${error.message}", error)
        }
        serverSocket = socket
        boundPort = socket.localPort
        CoreLog.i(TAG, "FTP server listening on port $boundPort")

        acceptJob = scope.launch(Dispatchers.IO) {
            acceptLoop(socket, this)
        }
    }

    /** Closes the listener and every session. Idempotent. */
    fun stop() {
        if (stopped) return
        stopped = true
        val socket = serverSocket
        serverSocket = null
        boundPort = -1
        closeQuietly(socket)
        acceptJob?.cancel()
        acceptJob = null
        for (session in sessions.toList()) {
            try {
                session.close()
            } catch (error: Exception) {
                CoreLog.d(TAG, "Cannot close an FTP session: ${error.message}")
            }
        }
        sessions.clear()
        CoreLog.i(TAG, "FTP server stopped")
    }

    private fun acceptLoop(socket: ServerSocket, scope: CoroutineScope) {
        while (!stopped && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (error: IOException) {
                if (stopped || socket.isClosed) break
                CoreLog.w(TAG, "FTP accept() failed, continuing", error)
                continue
            }
            if (sessions.size >= config.maxSessions) {
                rejectBusy(client)
                continue
            }
            val session = FtpSession(client, config, vfs, auth, transfers) { finished ->
                sessions.remove(finished)
            }
            sessions.add(session)
            scope.launch(sessionDispatcher) {
                try {
                    session.run()
                } catch (error: Throwable) {
                    // One broken client must never take down the listener.
                    CoreLog.w(TAG, "FTP session crashed", error)
                    sessions.remove(session)
                    closeQuietly(client)
                }
            }
        }
    }

    private fun rejectBusy(client: Socket) {
        try {
            client.getOutputStream().apply {
                write("421 Too many connections; try again later\r\n".toByteArray(Charsets.UTF_8))
                flush()
            }
        } catch (error: IOException) {
            CoreLog.d(TAG, "Could not write the 421 busy reply: ${error.message}")
        } finally {
            closeQuietly(client)
        }
    }

    private companion object {
        const val TAG = "FtpServer"
    }
}
