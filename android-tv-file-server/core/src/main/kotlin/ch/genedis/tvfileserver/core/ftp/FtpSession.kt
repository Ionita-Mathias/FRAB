package ch.genedis.tvfileserver.core.ftp

import ch.genedis.tvfileserver.core.auth.HttpAuthenticator
import ch.genedis.tvfileserver.core.transfer.TransferDirection
import ch.genedis.tvfileserver.core.transfer.TransferProtocol
import ch.genedis.tvfileserver.core.transfer.TransferRegistry
import ch.genedis.tvfileserver.core.util.CoreLog
import ch.genedis.tvfileserver.core.util.closeQuietly
import ch.genedis.tvfileserver.core.util.copyStream
import ch.genedis.tvfileserver.core.vfs.VPath
import ch.genedis.tvfileserver.core.vfs.VfsException
import ch.genedis.tvfileserver.core.vfs.VirtualFileSystem
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * One FTP control connection.
 *
 * Implements the RFC 959 core plus the RFC 2428 (EPSV/EPRT), RFC 3659 (MLST/MLSD/SIZE/MDTM/
 * REST) and UTF-8 extensions that current clients expect.
 */
internal class FtpSession(
    private val controlSocket: Socket,
    private val config: FtpConfig,
    private val vfs: VirtualFileSystem,
    private val auth: HttpAuthenticator,
    private val transfers: TransferRegistry,
    private val onClosed: (FtpSession) -> Unit,
) : Closeable {

    private val reader: BufferedReader = BufferedReader(
        InputStreamReader(controlSocket.getInputStream(), Charsets.UTF_8),
    )
    private val writer: BufferedWriter = BufferedWriter(
        OutputStreamWriter(controlSocket.getOutputStream(), Charsets.UTF_8),
    )
    private val data = FtpDataChannel(config)
    private val remote: String = controlSocket.inetAddress?.hostAddress ?: "unknown"
    private val buffer = ByteArray(config.bufferSize)

    private var pendingUser: String? = null
    private var authenticated = false
    private var username: String = ""
    private var readOnly = false
    private var workingDirectory: VPath = VPath.ROOT
    private var renameFrom: VPath? = null
    private var restartOffset: Long = 0
    private var epsvAll = false
    private var running = true

    @Volatile
    private var aborted = false

    fun run() {
        try {
            controlSocket.soTimeout = config.controlTimeoutMs
            reply(220, "${config.welcomeMessage} (UTF8)")
            while (running && !controlSocket.isClosed) {
                val line = try {
                    reader.readLine()
                } catch (error: SocketTimeoutException) {
                    reply(421, "Control connection timed out")
                    break
                } ?: break
                if (line.isEmpty()) continue
                handleCommand(line)
            }
        } catch (error: IOException) {
            CoreLog.d(TAG, "FTP session with $remote ended: ${error.message}")
        } catch (error: Exception) {
            CoreLog.w(TAG, "FTP session with $remote failed", error)
        } finally {
            close()
        }
    }

    override fun close() {
        running = false
        data.close()
        closeQuietly(reader)
        closeQuietly(writer)
        closeQuietly(controlSocket)
        onClosed(this)
    }

    // ------------------------------------------------------------------ dispatch

    private fun handleCommand(line: String) {
        val space = line.indexOf(' ')
        val verb = (if (space < 0) line else line.substring(0, space)).uppercase(Locale.ROOT).trim()
        val argument = if (space < 0) "" else line.substring(space + 1).trim()

        CoreLog.d(TAG, "$remote > $verb ${if (verb == "PASS") "****" else argument}")

        if (!authenticated && verb !in PRE_AUTH_COMMANDS) {
            reply(530, "Please log in with USER and PASS")
            return
        }

        when (verb) {
            "USER" -> user(argument)
            "PASS" -> pass(argument)
            "ACCT" -> reply(502, "ACCT is not supported")
            "AUTH" -> reply(502, "FTPS is not supported; use the WebDAV endpoint for encryption")
            "SYST" -> reply(215, "UNIX Type: L8")
            "FEAT" -> feat()
            "OPTS" -> opts(argument)
            "NOOP" -> reply(200, "OK")
            "HELP" -> reply(214, "Supported: ${SUPPORTED_COMMANDS.joinToString(" ")}")
            "QUIT" -> {
                reply(221, "Goodbye")
                running = false
            }
            "PWD", "XPWD" -> reply(257, "\"${escapePath(workingDirectory.value)}\" is the current directory")
            "CWD" -> cwd(argument)
            "CDUP", "XCUP" -> cwd("..")
            "TYPE" -> type(argument)
            "MODE" -> if (argument.uppercase(Locale.ROOT) == "S") reply(200, "Mode set to S") else reply(504, "Only stream mode is supported")
            "STRU" -> if (argument.uppercase(Locale.ROOT) == "F") reply(200, "Structure set to F") else reply(504, "Only file structure is supported")
            "PASV" -> pasv()
            "EPSV" -> epsv(argument)
            "PORT" -> port(argument)
            "EPRT" -> eprt(argument)
            "LIST" -> list(argument, Listing.LIST)
            "NLST" -> list(argument, Listing.NLST)
            "MLSD" -> list(argument, Listing.MLSD)
            "MLST" -> mlst(argument)
            "STAT" -> stat(argument)
            "SIZE" -> size(argument)
            "MDTM" -> mdtm(argument)
            "REST" -> rest(argument)
            "RETR" -> retr(argument)
            "STOR" -> stor(argument, append = false)
            "APPE" -> stor(argument, append = true)
            "STOU" -> stou()
            "DELE" -> dele(argument)
            "RMD", "XRMD" -> rmd(argument)
            "MKD", "XMKD" -> mkd(argument)
            "RNFR" -> rnfr(argument)
            "RNTO" -> rnto(argument)
            "ABOR" -> abor()
            "ALLO" -> reply(200, "OK")
            "SITE" -> reply(502, "SITE commands are not supported")
            else -> reply(500, "Unknown command: $verb")
        }
    }

    // ------------------------------------------------------------------ auth

    private fun user(argument: String) {
        if (argument.isEmpty()) {
            reply(501, "USER requires a name")
            return
        }
        pendingUser = argument
        if (!auth.policy.enabled) {
            acceptLogin(argument, readOnly = false)
            reply(230, "Logged in (authentication is disabled)")
            return
        }
        reply(331, "Password required for $argument")
    }

    private fun pass(argument: String) {
        val name = pendingUser
        if (name == null) {
            reply(503, "Send USER first")
            return
        }
        if (!auth.checkFtp(name, argument, remote)) {
            reply(530, "Login incorrect")
            pendingUser = null
            return
        }
        acceptLogin(name, auth.isFtpReadOnly(name))
        reply(230, if (readOnly) "Logged in with read-only access" else "Logged in")
    }

    private fun acceptLogin(name: String, readOnly: Boolean) {
        authenticated = true
        username = name
        this.readOnly = readOnly || vfs.readOnly
        pendingUser = null
    }

    // ------------------------------------------------------------------ navigation

    private fun feat() {
        writeLine("211-Extensions supported")
        for (feature in FEATURES) writeLine(" $feature")
        writeLine("211 End")
        flush()
    }

    private fun opts(argument: String) {
        val parts = argument.split(' ').filter { it.isNotEmpty() }
        if (parts.size >= 1 && parts[0].equals("UTF8", ignoreCase = true)) {
            reply(200, "UTF8 is always on")
            return
        }
        reply(501, "Unsupported option: $argument")
    }

    private fun cwd(argument: String) {
        val target = resolve(argument)
        val entry = vfs.stat(target)
        if (entry == null || !entry.isDirectory) {
            reply(550, "${display(argument)}: no such directory")
            return
        }
        workingDirectory = target
        reply(250, "Directory changed to ${escapePath(target.value)}")
    }

    private fun type(argument: String) {
        // Data is always moved verbatim: an Android device stores media files, and silently
        // rewriting line endings on a "text" transfer would corrupt them. The command is
        // still accepted because clients issue it unconditionally.
        val code = argument.trim().uppercase(Locale.ROOT).firstOrNull()
        when (code) {
            'A', 'I', 'L' -> reply(200, "Type set to $code (transfers are always binary)")
            else -> reply(504, "Unsupported type")
        }
    }

    // ------------------------------------------------------------------ data channel setup

    private fun pasv() {
        if (epsvAll) {
            reply(501, "PASV is not allowed after EPSV ALL")
            return
        }
        val address = controlSocket.localAddress ?: InetAddress.getLoopbackAddress()
        val port = try {
            data.listenPassive(address)
        } catch (error: IOException) {
            reply(425, "Cannot open a passive data port")
            return
        }
        val octets = address.address
        if (octets.size != 4) {
            reply(425, "Passive mode requires IPv4; use EPSV")
            return
        }
        val host = octets.joinToString(",") { (it.toInt() and 0xFF).toString() }
        reply(227, "Entering Passive Mode ($host,${port shr 8},${port and 0xFF})")
    }

    private fun epsv(argument: String) {
        if (argument.trim().equals("ALL", ignoreCase = true)) {
            epsvAll = true
            reply(200, "EPSV ALL accepted")
            return
        }
        val address = controlSocket.localAddress ?: InetAddress.getLoopbackAddress()
        val port = try {
            data.listenPassive(address)
        } catch (error: IOException) {
            reply(425, "Cannot open a passive data port")
            return
        }
        reply(229, "Entering Extended Passive Mode (|||$port|)")
    }

    private fun port(argument: String) {
        if (epsvAll) {
            reply(501, "PORT is not allowed after EPSV ALL")
            return
        }
        if (!config.allowActiveMode) {
            reply(502, "Active mode is disabled; use PASV")
            return
        }
        val parts = argument.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 6 || parts.any { it !in 0..255 }) {
            reply(501, "Malformed PORT argument")
            return
        }
        val host = "${parts[0]}.${parts[1]}.${parts[2]}.${parts[3]}"
        val dataPort = (parts[4] shl 8) or parts[5]
        armActive(host, dataPort)
    }

    private fun eprt(argument: String) {
        if (epsvAll) {
            reply(501, "EPRT is not allowed after EPSV ALL")
            return
        }
        if (!config.allowActiveMode) {
            reply(502, "Active mode is disabled; use EPSV")
            return
        }
        if (argument.length < 4) {
            reply(501, "Malformed EPRT argument")
            return
        }
        val delimiter = argument[0]
        val fields = argument.split(delimiter)
        if (fields.size < 4) {
            reply(501, "Malformed EPRT argument")
            return
        }
        val host = fields[2]
        val dataPort = fields[3].toIntOrNull()
        if (dataPort == null || dataPort !in 1..65535) {
            reply(501, "Malformed EPRT port")
            return
        }
        armActive(host, dataPort)
    }

    private fun armActive(host: String, dataPort: Int) {
        // FTP bounce protection: refuse to open a data connection to anywhere but the peer
        // that owns this control connection.
        val peer = controlSocket.inetAddress?.hostAddress
        val resolved = try {
            InetAddress.getByName(host)
        } catch (error: IOException) {
            reply(501, "Cannot resolve the data address")
            return
        }
        if (peer != null && resolved.hostAddress != peer) {
            CoreLog.w(TAG, "Refused an active data connection from $peer to ${resolved.hostAddress}")
            reply(501, "The data address must match the control connection")
            return
        }
        data.setActiveTarget(InetSocketAddress(resolved, dataPort))
        reply(200, "Active data connection accepted")
    }

    // ------------------------------------------------------------------ listings

    private enum class Listing { LIST, NLST, MLSD }

    private fun list(rawArgument: String, mode: Listing) {
        val argument = stripListOptions(rawArgument)
        val target = resolve(argument)
        val entry = vfs.stat(target)
        if (entry == null) {
            reply(550, "${display(argument)}: no such file or directory")
            return
        }

        val entries = try {
            if (entry.isDirectory) vfs.list(target) else listOf(entry)
        } catch (error: VfsException) {
            reply(550, error.message ?: "Cannot list the directory")
            return
        }

        val now = System.currentTimeMillis()
        val writable = !readOnly
        val payload = buildString {
            for (item in entries) {
                val line = when (mode) {
                    Listing.LIST -> FtpListFormatter.listLine(item, now)
                    Listing.NLST -> item.name
                    Listing.MLSD -> FtpListFormatter.mlsdLine(item, writable)
                }
                append(line).append("\r\n")
            }
        }.toByteArray(Charsets.UTF_8)

        transferOut(payload, "Directory listing")
    }

    private fun mlst(argument: String) {
        val target = resolve(argument)
        val entry = vfs.stat(target)
        if (entry == null) {
            reply(550, "${display(argument)}: no such file or directory")
            return
        }
        writeLine("250-Listing ${escapePath(target.value)}")
        writeLine(" " + FtpListFormatter.mlstLine(entry, !readOnly, target.value))
        writeLine("250 End")
        flush()
    }

    private fun stat(argument: String) {
        if (argument.isEmpty()) {
            writeLine("211-Status of the FTP session")
            writeLine(" Connected from $remote")
            writeLine(" Logged in as ${username.ifEmpty { "nobody" }}")
            writeLine(" Current directory: ${workingDirectory.value}")
            writeLine(" Access: ${if (readOnly) "read-only" else "read-write"}")
            writeLine("211 End of status")
            flush()
            return
        }
        val target = resolve(argument)
        val entry = vfs.stat(target)
        if (entry == null) {
            reply(550, "${display(argument)}: no such file or directory")
            return
        }
        val entries = if (entry.isDirectory) vfs.list(target) else listOf(entry)
        writeLine("213-Status of ${escapePath(target.value)}")
        val now = System.currentTimeMillis()
        for (item in entries) writeLine(" " + FtpListFormatter.listLine(item, now))
        writeLine("213 End of status")
        flush()
    }

    private fun stripListOptions(argument: String): String {
        // Clients send "LIST -al" or "LIST -la /path"; the flags are meaningless here.
        var rest = argument.trim()
        while (rest.startsWith("-")) {
            val space = rest.indexOf(' ')
            rest = if (space < 0) "" else rest.substring(space + 1).trim()
        }
        return rest
    }

    // ------------------------------------------------------------------ metadata

    private fun size(argument: String) {
        val target = resolve(argument)
        val entry = vfs.stat(target)
        when {
            entry == null -> reply(550, "${display(argument)}: no such file")
            entry.isDirectory -> reply(550, "${display(argument)} is a directory")
            else -> reply(213, entry.size.toString())
        }
    }

    private fun mdtm(argument: String) {
        val target = resolve(argument)
        val entry = vfs.stat(target)
        if (entry == null) {
            reply(550, "${display(argument)}: no such file")
            return
        }
        reply(213, FtpListFormatter.formatModifyTime(entry.lastModified))
    }

    private fun rest(argument: String) {
        val offset = argument.trim().toLongOrNull()
        if (offset == null || offset < 0) {
            reply(501, "Malformed restart offset")
            return
        }
        restartOffset = offset
        reply(350, "Restarting at $offset; send RETR or STOR")
    }

    // ------------------------------------------------------------------ transfers

    private fun retr(argument: String) {
        val target = resolve(argument)
        val entry = vfs.stat(target)
        if (entry == null || entry.isDirectory) {
            restartOffset = 0
            reply(550, "${display(argument)}: no such file")
            return
        }
        val offset = restartOffset
        restartOffset = 0
        if (offset > entry.size) {
            reply(554, "Restart offset is past the end of the file")
            return
        }

        val remaining = entry.size - offset
        withDataConnection("Opening data connection for ${entry.name} ($remaining bytes)") { socket ->
            val handle = transfers.begin(
                name = entry.name,
                path = target.value,
                direction = TransferDirection.DOWNLOAD,
                protocol = TransferProtocol.FTP,
                client = remote,
                total = remaining,
            )
            try {
                vfs.openRead(target, offset).use { input ->
                    copyStream(
                        input,
                        socket.getOutputStream(),
                        buffer,
                        onProgress = { handle.advance(it) },
                        isActive = { !aborted },
                    )
                }
                socket.getOutputStream().flush()
                handle.complete()
            } catch (error: IOException) {
                handle.fail(error)
                throw error
            }
        }
    }

    private fun stor(argument: String, append: Boolean) {
        if (readOnly) {
            reply(553, "The server is in read-only mode")
            return
        }
        if (argument.isEmpty()) {
            reply(501, "A file name is required")
            return
        }
        val target = resolve(argument)
        val offset = restartOffset
        restartOffset = 0

        val existing = vfs.stat(target)
        if (existing != null && existing.isDirectory) {
            reply(550, "${display(argument)} is a directory")
            return
        }
        val appendMode = when {
            append -> true
            offset == 0L -> false
            existing != null && offset == existing.size -> true
            else -> {
                reply(554, "Cannot restart a store at offset $offset")
                return
            }
        }
        storeToPath(target, appendMode, "Opening data connection for ${target.name}")
    }

    private fun storeToPath(target: VPath, appendMode: Boolean, description: String) {
        withDataConnection(description) { socket ->
            val handle = transfers.begin(
                name = target.name,
                path = target.value,
                direction = TransferDirection.UPLOAD,
                protocol = TransferProtocol.FTP,
                client = remote,
                total = -1,
            )
            try {
                vfs.openWrite(target, appendMode).use { output ->
                    copyStream(
                        socket.getInputStream(),
                        output,
                        buffer,
                        onProgress = { handle.advance(it) },
                        isActive = { !aborted },
                    )
                }
                handle.complete()
            } catch (error: IOException) {
                handle.fail(error)
                throw error
            }
        }
    }

    private fun stou() {
        if (readOnly) {
            reply(553, "The server is in read-only mode")
            return
        }
        var candidate = workingDirectory.child("upload-${System.currentTimeMillis()}.tmp")
        var counter = 1
        while (vfs.stat(candidate) != null && counter < 1000) {
            candidate = workingDirectory.child("upload-${System.currentTimeMillis()}-$counter.tmp")
            counter++
        }
        restartOffset = 0
        // RFC 1123 requires the chosen name to travel in the 150 reply itself.
        storeToPath(candidate, appendMode = false, description = "FILE: ${candidate.name}")
    }

    private fun dele(argument: String) {
        if (readOnly) {
            reply(553, "The server is in read-only mode")
            return
        }
        val target = resolve(argument)
        val entry = vfs.stat(target)
        if (entry == null || entry.isDirectory) {
            reply(550, "${display(argument)}: no such file")
            return
        }
        runVfs(argument) {
            vfs.delete(target, recursive = false)
            reply(250, "File deleted")
        }
    }

    private fun rmd(argument: String) {
        if (readOnly) {
            reply(553, "The server is in read-only mode")
            return
        }
        val target = resolve(argument)
        val entry = vfs.stat(target)
        if (entry == null || !entry.isDirectory) {
            reply(550, "${display(argument)}: no such directory")
            return
        }
        runVfs(argument) {
            vfs.delete(target, recursive = true)
            reply(250, "Directory removed")
        }
    }

    private fun mkd(argument: String) {
        if (readOnly) {
            reply(553, "The server is in read-only mode")
            return
        }
        if (argument.isEmpty()) {
            reply(501, "A directory name is required")
            return
        }
        val target = resolve(argument)
        runVfs(argument) {
            vfs.mkdir(target)
            reply(257, "\"${escapePath(target.value)}\" created")
        }
    }

    private fun rnfr(argument: String) {
        if (readOnly) {
            reply(553, "The server is in read-only mode")
            return
        }
        val target = resolve(argument)
        if (vfs.stat(target) == null) {
            reply(550, "${display(argument)}: no such file or directory")
            return
        }
        renameFrom = target
        reply(350, "Ready for RNTO")
    }

    private fun rnto(argument: String) {
        val source = renameFrom
        if (source == null) {
            reply(503, "Send RNFR first")
            return
        }
        renameFrom = null
        val target = resolve(argument)
        runVfs(argument) {
            vfs.move(source, target, overwrite = false)
            reply(250, "Renamed")
        }
    }

    private fun abor() {
        aborted = true
        data.abortCurrent()
        reply(226, "Abort processed")
        aborted = false
    }

    /** Sends a small in-memory payload (a listing) over the data connection. */
    private fun transferOut(payload: ByteArray, description: String) {
        withDataConnection(description) { socket ->
            socket.getOutputStream().apply {
                write(payload)
                flush()
            }
        }
    }

    /**
     * Runs [block] with an open data connection, sending the 150/226 pair around it.
     *
     * Every failure path resets the channel so a client that retries is not stuck talking to
     * a stale listener.
     */
    private fun withDataConnection(description: String, block: (Socket) -> Unit) {
        if (!data.isArmed) {
            reply(425, "Use PASV or PORT first")
            return
        }
        reply(150, description)
        var socket: Socket? = null
        try {
            socket = data.open()
            block(socket)
            reply(226, "Transfer complete")
        } catch (error: SocketTimeoutException) {
            reply(425, "The data connection timed out")
        } catch (error: VfsException) {
            reply(550, error.message ?: "Transfer failed")
        } catch (error: IOException) {
            if (aborted) {
                reply(426, "Transfer aborted")
            } else {
                CoreLog.w(TAG, "FTP transfer failed for $remote", error)
                reply(451, "Transfer failed: ${error.message}")
            }
        } finally {
            closeQuietly(socket)
            data.reset()
        }
    }

    private inline fun runVfs(argument: String, block: () -> Unit) {
        try {
            block()
        } catch (error: VfsException) {
            reply(replyCodeFor(error), "${display(argument)}: ${error.message}")
        } catch (error: IOException) {
            reply(451, "${display(argument)}: ${error.message}")
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun resolve(argument: String): VPath {
        val trimmed = argument.trim().removeSurrounding("\"")
        if (trimmed.isEmpty()) return workingDirectory
        return if (trimmed.startsWith("/")) VPath.of(trimmed) else workingDirectory.resolve(trimmed)
    }

    private fun display(argument: String): String = argument.ifEmpty { workingDirectory.value }

    private fun escapePath(path: String): String = path.replace("\"", "\"\"")

    private fun reply(code: Int, message: String) {
        // A multi-line message would break the single-line reply contract, so fold it.
        writeLine("$code ${message.replace('\r', ' ').replace('\n', ' ')}")
        flush()
    }

    private fun writeLine(line: String) {
        writer.write(line)
        writer.write("\r\n")
    }

    private fun flush() {
        try {
            writer.flush()
        } catch (error: IOException) {
            CoreLog.d(TAG, "Cannot flush the control channel to $remote: ${error.message}")
            running = false
        }
    }

    private companion object {
        const val TAG = "FtpSession"

        val PRE_AUTH_COMMANDS = setOf("USER", "PASS", "QUIT", "FEAT", "OPTS", "SYST", "NOOP", "AUTH", "HELP")

        val FEATURES = listOf(
            "UTF8",
            "MLST type*;size*;modify*;perm*;",
            "MLSD",
            "SIZE",
            "MDTM",
            "REST STREAM",
            "EPSV",
            "TVFS",
        )

        val SUPPORTED_COMMANDS = listOf(
            "USER", "PASS", "SYST", "FEAT", "OPTS", "PWD", "CWD", "CDUP", "TYPE", "MODE",
            "STRU", "PASV", "EPSV", "PORT", "EPRT", "LIST", "NLST", "MLSD", "MLST", "STAT",
            "SIZE", "MDTM", "REST", "RETR", "STOR", "STOU", "APPE", "DELE", "RMD", "MKD",
            "RNFR", "RNTO", "ABOR", "NOOP", "QUIT", "HELP", "ALLO",
        )

        fun replyCodeFor(error: VfsException): Int = when (error.reason) {
            VfsException.Reason.READ_ONLY, VfsException.Reason.ACCESS_DENIED -> 550
            VfsException.Reason.NO_SPACE -> 452
            VfsException.Reason.ALREADY_EXISTS, VfsException.Reason.CONFLICT -> 550
            else -> 550
        }
    }
}
