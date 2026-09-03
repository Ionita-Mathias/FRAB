package ch.genedis.tvfileserver.core

import ch.genedis.tvfileserver.core.auth.AuthPolicy
import ch.genedis.tvfileserver.core.auth.Credentials
import ch.genedis.tvfileserver.core.auth.HttpAuthenticator
import ch.genedis.tvfileserver.core.ftp.FtpConfig
import ch.genedis.tvfileserver.core.ftp.FtpServer
import ch.genedis.tvfileserver.core.transfer.TransferRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket

class FtpServerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var server: FtpServer
    private lateinit var storage: File
    private var port = 0

    @Before
    fun setUp() {
        storage = temporaryFolder.newFolder("storage")
        File(storage, "Movies").mkdirs()
        File(storage, "Movies/film.mkv").writeText("video-payload")
        File(storage, "readme.txt").writeText("hello world")

        val auth = HttpAuthenticator(
            AuthPolicy(enabled = true, credentials = Credentials("tv", "secret"), allowAnonymousRead = false),
        )
        server = FtpServer(
            FtpConfig(port = 0, passivePortStart = 0, passivePortEnd = 0),
            testFileSystem(storage),
            auth,
            TransferRegistry(),
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server.start(scope)
        port = server.boundPort
        assertTrue(port > 0)
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    /** A minimal synchronous FTP client, so the protocol itself is what gets exercised. */
    private inner class Client : Closeable {
        val socket = Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 10_000 }
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

        fun readReply(): String {
            val first = reader.readLine() ?: error("connection closed")
            if (first.length < 4 || first[3] != '-') return first
            val code = first.substring(0, 3)
            val builder = StringBuilder(first)
            while (true) {
                val line = reader.readLine() ?: break
                builder.append('\n').append(line)
                if (line.startsWith("$code ")) break
            }
            return builder.toString()
        }

        fun send(command: String): String {
            writer.write(command)
            writer.write("\r\n")
            writer.flush()
            return readReply()
        }

        fun login(user: String = "tv", password: String = "secret"): String {
            send("USER $user")
            return send("PASS $password")
        }

        /** Issues PASV and returns a socket connected to the announced data port. */
        fun passive(): Socket {
            val reply = send("PASV")
            assertTrue("expected 227, got $reply", reply.startsWith("227"))
            val numbers = reply.substringAfter('(').substringBefore(')').split(',').map { it.trim().toInt() }
            val dataPort = (numbers[4] shl 8) or numbers[5]
            return Socket(InetAddress.getLoopbackAddress(), dataPort).apply { soTimeout = 10_000 }
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private fun connected(): Client {
        val client = Client()
        val banner = client.readReply()
        assertTrue("expected a 220 banner, got $banner", banner.startsWith("220"))
        return client
    }

    @Test(timeout = 15_000)
    fun `greets and logs in`() {
        connected().use { client ->
            assertTrue(client.send("USER tv").startsWith("331"))
            assertTrue(client.send("PASS secret").startsWith("230"))
            assertTrue(client.send("SYST").startsWith("215"))
            assertTrue(client.send("QUIT").startsWith("221"))
        }
    }

    @Test(timeout = 15_000)
    fun `rejects a wrong password`() {
        connected().use { client ->
            client.send("USER tv")
            assertTrue(client.send("PASS wrong").startsWith("530"))
            assertTrue("commands must stay blocked", client.send("PWD").startsWith("530"))
        }
    }

    @Test(timeout = 15_000)
    fun `advertises its extensions`() {
        connected().use { client ->
            client.login()
            val feat = client.send("FEAT")
            assertTrue(feat.startsWith("211"))
            for (feature in listOf("UTF8", "MLSD", "SIZE", "MDTM", "REST STREAM", "EPSV", "TVFS")) {
                assertTrue("FEAT must list $feature, got:\n$feat", feat.contains(feature))
            }
            assertTrue(client.send("OPTS UTF8 ON").startsWith("200"))
        }
    }

    @Test(timeout = 15_000)
    fun `navigates the virtual tree`() {
        connected().use { client ->
            client.login()
            assertTrue(client.send("PWD").contains("\"/\""))
            assertTrue(client.send("CWD /data").startsWith("250"))
            assertTrue(client.send("PWD").contains("\"/data\""))
            assertTrue(client.send("CWD Movies").startsWith("250"))
            assertTrue(client.send("PWD").contains("\"/data/Movies\""))
            assertTrue(client.send("CDUP").startsWith("250"))
            assertTrue(client.send("PWD").contains("\"/data\""))
            assertTrue(client.send("CWD /nope").startsWith("550"))
        }
    }

    @Test(timeout = 15_000)
    fun `LIST returns a unix style listing`() {
        connected().use { client ->
            client.login()
            client.send("CWD /data")
            val data = client.passive()
            val reply = client.send("LIST -al")
            assertTrue("expected 150, got $reply", reply.startsWith("150"))
            val listing = data.use { String(it.getInputStream().readBytes(), Charsets.UTF_8) }
            assertTrue(client.readReply().startsWith("226"))

            assertTrue(listing.contains("readme.txt"))
            assertTrue(listing.contains("Movies"))
            assertTrue("directories must be flagged with d, got:\n$listing", listing.lines().any { it.startsWith("d") && it.contains("Movies") })
            assertTrue(listing.lines().any { it.contains("11") && it.contains("readme.txt") })
        }
    }

    @Test(timeout = 15_000)
    fun `MLSD returns machine readable facts`() {
        connected().use { client ->
            client.login()
            client.send("CWD /data")
            val data = client.passive()
            assertTrue(client.send("MLSD").startsWith("150"))
            val listing = data.use { String(it.getInputStream().readBytes(), Charsets.UTF_8) }
            assertTrue(client.readReply().startsWith("226"))

            assertTrue(listing.contains("type=dir;"))
            assertTrue(listing.contains("type=file;"))
            assertTrue(listing.contains("size=11;"))
            assertTrue(listing.contains("modify="))
        }
    }

    @Test(timeout = 15_000)
    fun `NLST returns bare names`() {
        connected().use { client ->
            client.login()
            client.send("CWD /data")
            val data = client.passive()
            client.send("NLST")
            val listing = data.use { String(it.getInputStream().readBytes(), Charsets.UTF_8) }
            client.readReply()
            assertEquals(listOf("Movies", "readme.txt"), listing.trim().lines().sorted())
        }
    }

    @Test(timeout = 15_000)
    fun `RETR downloads a file`() {
        connected().use { client ->
            client.login()
            val data = client.passive()
            assertTrue(client.send("RETR /data/readme.txt").startsWith("150"))
            val payload = data.use { String(it.getInputStream().readBytes(), Charsets.UTF_8) }
            assertTrue(client.readReply().startsWith("226"))
            assertEquals("hello world", payload)
        }
    }

    @Test(timeout = 15_000)
    fun `REST resumes a download`() {
        connected().use { client ->
            client.login()
            assertTrue(client.send("REST 6").startsWith("350"))
            val data = client.passive()
            client.send("RETR /data/readme.txt")
            val payload = data.use { String(it.getInputStream().readBytes(), Charsets.UTF_8) }
            client.readReply()
            assertEquals("world", payload)
        }
    }

    @Test(timeout = 15_000)
    fun `STOR uploads a file`() {
        connected().use { client ->
            client.login()
            client.send("CWD /data")
            val data = client.passive()
            assertTrue(client.send("STOR uploaded.bin").startsWith("150"))
            data.use { it.getOutputStream().write("ftp-upload".toByteArray()) }
            assertTrue(client.readReply().startsWith("226"))
            assertEquals("ftp-upload", File(storage, "uploaded.bin").readText())
        }
    }

    @Test(timeout = 15_000)
    fun `APPE appends to a file`() {
        connected().use { client ->
            client.login()
            client.send("CWD /data")
            val data = client.passive()
            client.send("APPE readme.txt")
            data.use { it.getOutputStream().write("!".toByteArray()) }
            client.readReply()
            assertEquals("hello world!", File(storage, "readme.txt").readText())
        }
    }

    @Test(timeout = 15_000)
    fun `SIZE and MDTM report metadata`() {
        connected().use { client ->
            client.login()
            assertEquals("213 11", client.send("SIZE /data/readme.txt"))
            assertTrue(client.send("MDTM /data/readme.txt").matches(Regex("213 \\d{14}")))
            assertTrue(client.send("SIZE /data/Movies").startsWith("550"))
        }
    }

    @Test(timeout = 15_000)
    fun `MLST describes a single entry`() {
        connected().use { client ->
            client.login()
            val reply = client.send("MLST /data/readme.txt")
            assertTrue(reply.startsWith("250-"))
            assertTrue(reply.contains("type=file;"))
            assertTrue(reply.contains("/data/readme.txt"))
        }
    }

    @Test(timeout = 15_000)
    fun `creates renames and removes`() {
        connected().use { client ->
            client.login()
            client.send("CWD /data")
            assertTrue(client.send("MKD Series").startsWith("257"))
            assertTrue(File(storage, "Series").isDirectory)

            assertTrue(client.send("RNFR Series").startsWith("350"))
            assertTrue(client.send("RNTO Shows").startsWith("250"))
            assertTrue(File(storage, "Shows").isDirectory)
            assertFalse(File(storage, "Series").exists())

            assertTrue(client.send("RMD Shows").startsWith("250"))
            assertFalse(File(storage, "Shows").exists())

            assertTrue(client.send("DELE readme.txt").startsWith("250"))
            assertFalse(File(storage, "readme.txt").exists())
        }
    }

    @Test(timeout = 15_000)
    fun `RNTO without RNFR is refused`() {
        connected().use { client ->
            client.login()
            assertTrue(client.send("RNTO whatever").startsWith("503"))
        }
    }

    @Test(timeout = 15_000)
    fun `EPSV works and EPSV ALL disables PASV`() {
        connected().use { client ->
            client.login()
            val reply = client.send("EPSV")
            assertTrue("expected 229, got $reply", reply.startsWith("229"))
            val dataPort = reply.substringAfter("(|||").substringBefore("|)").toInt()
            Socket(InetAddress.getLoopbackAddress(), dataPort).use { data ->
                client.send("NLST /data")
                val listing = String(data.getInputStream().readBytes(), Charsets.UTF_8)
                client.readReply()
                assertTrue(listing.contains("readme.txt"))
            }

            assertTrue(client.send("EPSV ALL").startsWith("200"))
            assertTrue(client.send("PASV").startsWith("501"))
        }
    }

    @Test(timeout = 15_000)
    fun `a transfer without a data connection is refused`() {
        connected().use { client ->
            client.login()
            assertTrue(client.send("RETR /data/readme.txt").startsWith("425"))
        }
    }

    @Test(timeout = 15_000)
    fun `refuses an active connection to a third party`() {
        connected().use { client ->
            client.login()
            // 203.0.113.9 is a TEST-NET address, definitely not this client: the server must
            // refuse to be used as an FTP bounce relay.
            assertTrue(client.send("PORT 203,0,113,9,10,10").startsWith("501"))
        }
    }

    @Test(timeout = 15_000)
    fun `TYPE and STRU are accepted`() {
        connected().use { client ->
            client.login()
            assertTrue(client.send("TYPE I").startsWith("200"))
            assertTrue(client.send("TYPE A").startsWith("200"))
            assertTrue(client.send("TYPE X").startsWith("504"))
            assertTrue(client.send("STRU F").startsWith("200"))
            assertTrue(client.send("STRU R").startsWith("504"))
            assertTrue(client.send("MODE S").startsWith("200"))
            assertTrue(client.send("NOOP").startsWith("200"))
        }
    }

    @Test(timeout = 15_000)
    fun `STAT reports the session`() {
        connected().use { client ->
            client.login()
            val reply = client.send("STAT")
            assertTrue(reply.startsWith("211"))
            assertTrue(reply.contains("Logged in as tv"))
        }
    }

    @Test(timeout = 15_000)
    fun `unknown commands do not kill the session`() {
        connected().use { client ->
            client.login()
            assertTrue(client.send("FROBNICATE").startsWith("500"))
            assertTrue(client.send("PWD").startsWith("257"))
        }
    }

    @Test(timeout = 15_000)
    fun `serves several sessions at once`() {
        val first = connected()
        val second = connected()
        try {
            first.login()
            second.login()
            assertTrue(first.send("PWD").startsWith("257"))
            assertTrue(second.send("PWD").startsWith("257"))
            awaitTrue { server.sessionCount >= 2 }
        } finally {
            first.close()
            second.close()
        }
    }

    @Test(timeout = 15_000)
    fun `stop closes the listener`() {
        server.stop()
        var refused = false
        try {
            Socket(InetAddress.getLoopbackAddress(), port).use { it.getInputStream().read() }
        } catch (error: Exception) {
            refused = true
        }
        assertTrue(refused)
    }
}
