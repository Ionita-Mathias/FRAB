package ch.genedis.tvfileserver.core.ftp

import ch.genedis.tvfileserver.core.util.CoreLog
import ch.genedis.tvfileserver.core.util.closeQuietly
import java.io.Closeable
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The data connection of one FTP session.
 *
 * Exactly one of the two modes is armed at a time: passive (the server listens and the
 * client connects) or active (the client listens and the server connects). Passive is what
 * every modern client uses; active is kept for old set-top clients that still default to it.
 */
class FtpDataChannel(private val config: FtpConfig) : Closeable {

    private var passiveListener: ServerSocket? = null
    private var activeTarget: InetSocketAddress? = null

    @Volatile
    private var current: Socket? = null

    val isArmed: Boolean get() = passiveListener != null || activeTarget != null

    /**
     * Opens a passive listener on a port from the configured range.
     *
     * @return the bound port.
     * @throws IOException when the whole range is taken and the ephemeral fallback fails too.
     */
    fun listenPassive(bindAddress: InetAddress): Int {
        reset()
        for (port in config.passivePortStart..config.passivePortEnd) {
            val socket = tryBind(bindAddress, port)
            if (socket != null) {
                passiveListener = socket
                return socket.localPort
            }
        }
        // Every configured port is busy. An ephemeral port still works for clients on the
        // same LAN, so prefer a working transfer over a clean failure.
        val fallback = tryBind(bindAddress, 0)
            ?: throw IOException("No free passive data port")
        CoreLog.w(TAG, "Passive port range exhausted, fell back to ${fallback.localPort}")
        passiveListener = fallback
        return fallback.localPort
    }

    /** Arms active mode: the server will connect out to [address]. */
    fun setActiveTarget(address: InetSocketAddress) {
        reset()
        activeTarget = address
    }

    /**
     * Establishes the data connection.
     *
     * In passive mode this blocks until the client connects or [FtpConfig.dataTimeoutMs]
     * elapses; in active mode it dials the client.
     */
    fun open(): Socket {
        val listener = passiveListener
        val target = activeTarget
        val socket = when {
            listener != null -> {
                listener.soTimeout = config.dataTimeoutMs
                listener.accept()
            }
            target != null -> Socket().apply {
                soTimeout = config.dataTimeoutMs
                connect(target, config.dataTimeoutMs)
            }
            else -> throw IOException("No data connection was set up")
        }
        socket.soTimeout = config.dataTimeoutMs
        socket.tcpNoDelay = true
        current = socket
        return socket
    }

    /** Closes the live transfer socket, which is how `ABOR` interrupts a transfer. */
    fun abortCurrent() {
        closeQuietly(current)
        current = null
    }

    /** Releases the listener and target so the next transfer must re-arm. */
    fun reset() {
        closeQuietly(passiveListener)
        passiveListener = null
        activeTarget = null
        closeQuietly(current)
        current = null
    }

    override fun close() = reset()

    private fun tryBind(bindAddress: InetAddress, port: Int): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, port), 1)
        }
    } catch (error: BindException) {
        null
    } catch (error: IOException) {
        CoreLog.d(TAG, "Cannot bind data port $port: ${error.message}")
        null
    }

    private companion object {
        const val TAG = "FtpDataChannel"
    }
}
