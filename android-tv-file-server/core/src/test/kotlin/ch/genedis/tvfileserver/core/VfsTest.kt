package ch.genedis.tvfileserver.core

import ch.genedis.tvfileserver.core.vfs.LocalFileSystem
import ch.genedis.tvfileserver.core.vfs.VPath
import ch.genedis.tvfileserver.core.vfs.VfsException
import ch.genedis.tvfileserver.core.vfs.VfsRoot
import ch.genedis.tvfileserver.core.vfs.VfsRootType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class VPathTest {

    @Test
    fun `normalises separators and dot segments`() {
        assertEquals("/", VPath.of("").value)
        assertEquals("/", VPath.of("/").value)
        assertEquals("/", VPath.of("///").value)
        assertEquals("/a/b", VPath.of("/a//b").value)
        assertEquals("/a/b", VPath.of("a/b").value)
        assertEquals("/a/b", VPath.of("/a/./b").value)
        assertEquals("/a", VPath.of("/a/b/..").value)
        assertEquals("/b", VPath.of("/a/../b").value)
        assertEquals("/a/b", VPath.of("\\a\\b").value)
    }

    @Test
    fun `never escapes the root`() {
        assertEquals("/", VPath.of("..").value)
        assertEquals("/", VPath.of("../../..").value)
        assertEquals("/etc", VPath.of("/../../etc").value)
        assertEquals("/etc/passwd", VPath.of("/a/../../../etc/passwd").value)
    }

    @Test
    fun `exposes name parent and segments`() {
        val path = VPath.of("/internal/Movies/film.mkv")
        assertEquals("film.mkv", path.name)
        assertEquals("/internal/Movies", path.parent!!.value)
        assertEquals(listOf("internal", "Movies", "film.mkv"), path.segments)
        assertEquals("internal", path.rootId)
        assertFalse(path.isRoot)

        assertTrue(VPath.ROOT.isRoot)
        assertNull(VPath.ROOT.parent)
        assertEquals("", VPath.ROOT.name)
        assertEquals(emptyList<String>(), VPath.ROOT.segments)
        assertNull(VPath.ROOT.rootId)
    }

    @Test
    fun `startsWith treats the root as an ancestor of everything`() {
        val parent = VPath.of("/internal/Movies")
        assertTrue(VPath.of("/internal/Movies/a.mkv").startsWith(parent))
        assertTrue(parent.startsWith(parent))
        assertTrue(parent.startsWith(VPath.ROOT))
        assertFalse(VPath.of("/internal/MoviesOther").startsWith(parent))
    }

    @Test
    fun `rejects invalid segments`() {
        assertFalse(VPath.isValidSegment(""))
        assertFalse(VPath.isValidSegment("."))
        assertFalse(VPath.isValidSegment(".."))
        assertFalse(VPath.isValidSegment("a/b"))
        assertFalse(VPath.isValidSegment("a\\b"))
        assertFalse(VPath.isValidSegment("a\u0000b"))
        assertFalse(VPath.isValidSegment("trailing "))
        assertTrue(VPath.isValidSegment("Some File (2).mkv"))
        assertTrue(VPath.isValidSegment("été"))
    }

    @Test
    fun `ofOrNull rejects a structurally invalid path`() {
        assertNull(VPath.ofOrNull("/a/b\u0000c"))
        assertNotNull(VPath.ofOrNull("/a/b c"))
    }

    @Test
    fun `equality and ordering follow the string value`() {
        assertEquals(VPath.of("/a/b"), VPath.of("/a//b/"))
        assertEquals(VPath.of("/a/b").hashCode(), VPath.of("/a/b").hashCode())
        assertTrue(VPath.of("/a").compareTo(VPath.of("/b")) < 0)
    }
}

class LocalFileSystemTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var storage: File
    private lateinit var fs: LocalFileSystem

    @org.junit.Before
    fun setUp() {
        storage = temporaryFolder.newFolder("storage")
        File(storage, "Movies").mkdirs()
        File(storage, "Movies/film.mkv").writeText("video-bytes")
        File(storage, "notes.txt").writeText("hello")
        File(storage, ".hidden").writeText("secret")
        fs = testFileSystem(storage)
    }

    @Test
    fun `root lists the storage roots`() {
        val roots = fs.list(VPath.ROOT)
        assertEquals(1, roots.size)
        assertEquals("Test storage", roots[0].name)
        assertTrue(roots[0].isDirectory)
        assertEquals("data", roots[0].rootId)
        assertEquals("/data", roots[0].path.value)
    }

    @Test
    fun `lists directories first then case-insensitively by name`() {
        File(storage, "Zeta").mkdirs()
        File(storage, "alpha.txt").writeText("a")
        val names = fs.list(VPath.of("/data")).map { it.name }
        assertEquals(listOf("Movies", "Zeta"), names.take(2))
        assertTrue(names.indexOf("alpha.txt") < names.indexOf("notes.txt"))
    }

    @Test
    fun `stats a file`() {
        val entry = fs.stat(VPath.of("/data/notes.txt"))!!
        assertFalse(entry.isDirectory)
        assertEquals(5, entry.size)
        assertEquals("text/plain", entry.mimeType)
        assertFalse(entry.isHidden)
    }

    @Test
    fun `marks dot files hidden`() {
        assertTrue(fs.stat(VPath.of("/data/.hidden"))!!.isHidden)
    }

    @Test
    fun `returns null for a missing path and for an unknown root`() {
        assertNull(fs.stat(VPath.of("/data/nope.txt")))
        assertNull(fs.stat(VPath.of("/other/file.txt")))
    }

    @Test
    fun `reads and writes streams`() {
        fs.openWrite(VPath.of("/data/new/dir/file.bin")).use { it.write(byteArrayOf(1, 2, 3, 4, 5)) }
        assertEquals(5, fs.stat(VPath.of("/data/new/dir/file.bin"))!!.size)

        val bytes = fs.openRead(VPath.of("/data/new/dir/file.bin"), offset = 2).use { it.readBytes() }
        assertEquals(listOf<Byte>(3, 4, 5), bytes.toList())
    }

    @Test
    fun `appends when asked`() {
        fs.openWrite(VPath.of("/data/append.txt")).use { it.write("one".toByteArray()) }
        fs.openWrite(VPath.of("/data/append.txt"), append = true).use { it.write("-two".toByteArray()) }
        assertEquals("one-two", File(storage, "append.txt").readText())
    }

    @Test
    fun `mkdir rejects an existing path`() {
        fs.mkdir(VPath.of("/data/fresh"))
        assertTrue(File(storage, "fresh").isDirectory)
        val error = runCatching { fs.mkdir(VPath.of("/data/fresh")) }.exceptionOrNull()
        assertEquals(VfsException.Reason.ALREADY_EXISTS, (error as VfsException).reason)
    }

    @Test
    fun `delete refuses a non-empty directory unless recursive`() {
        val error = runCatching { fs.delete(VPath.of("/data/Movies"), recursive = false) }.exceptionOrNull()
        assertEquals(VfsException.Reason.CONFLICT, (error as VfsException).reason)

        fs.delete(VPath.of("/data/Movies"), recursive = true)
        assertFalse(File(storage, "Movies").exists())
    }

    @Test
    fun `delete refuses the storage root itself`() {
        val error = runCatching { fs.delete(VPath.of("/data"), recursive = true) }.exceptionOrNull()
        assertEquals(VfsException.Reason.ACCESS_DENIED, (error as VfsException).reason)
        assertTrue(storage.isDirectory)
    }

    @Test
    fun `moves and copies`() {
        fs.move(VPath.of("/data/notes.txt"), VPath.of("/data/Movies/notes.txt"), overwrite = false)
        assertFalse(File(storage, "notes.txt").exists())
        assertEquals("hello", File(storage, "Movies/notes.txt").readText())

        fs.copy(VPath.of("/data/Movies"), VPath.of("/data/MoviesCopy"), overwrite = false, recursive = true)
        assertEquals("hello", File(storage, "MoviesCopy/notes.txt").readText())
        assertEquals("video-bytes", File(storage, "MoviesCopy/film.mkv").readText())
    }

    @Test
    fun `copy of a directory requires recursion`() {
        val error = runCatching {
            fs.copy(VPath.of("/data/Movies"), VPath.of("/data/X"), overwrite = false, recursive = false)
        }.exceptionOrNull()
        assertEquals(VfsException.Reason.IS_A_DIRECTORY, (error as VfsException).reason)
    }

    @Test
    fun `move refuses to nest a directory inside itself`() {
        val error = runCatching {
            fs.move(VPath.of("/data/Movies"), VPath.of("/data/Movies/inner"), overwrite = false)
        }.exceptionOrNull()
        assertEquals(VfsException.Reason.CONFLICT, (error as VfsException).reason)
    }

    @Test
    fun `refuses to escape the root with dot dot`() {
        // VPath already collapses "..", so the escape is attempted at the file layer by
        // handing the resolver a root whose child would climb out.
        assertNull(fs.stat(VPath.of("/data/../../etc/passwd")))
        assertNull(fs.stat(VPath.of("/../etc/passwd")))
    }

    @Test
    fun `refuses to follow a symlink that leaves the root`() {
        Assume.assumeFalse(System.getProperty("os.name").lowercase(Locale.ROOT).contains("win"))
        val outside = temporaryFolder.newFolder("outside")
        File(outside, "secret.txt").writeText("do-not-serve")

        val linked = ProcessBuilder("ln", "-s", outside.absolutePath, File(storage, "escape").absolutePath)
            .redirectErrorStream(true)
            .start()
            .waitFor() == 0
        Assume.assumeTrue("symlink creation is unavailable", linked)

        val error = runCatching { fs.stat(VPath.of("/data/escape/secret.txt")) }.exceptionOrNull()
        assertTrue(
            "reading through an escaping symlink must be denied, got $error",
            error is VfsException && error.reason == VfsException.Reason.ACCESS_DENIED,
        )
    }

    @Test
    fun `refuses writes on a read-only filesystem`() {
        val readOnly = testFileSystem(storage, readOnly = true)
        val error = runCatching { readOnly.openWrite(VPath.of("/data/x.txt")) }.exceptionOrNull()
        assertEquals(VfsException.Reason.READ_ONLY, (error as VfsException).reason)
        assertFalse(readOnly.isWritable(VPath.of("/data/x.txt")))
    }

    @Test
    fun `refuses writes on a non-writable root`() {
        val fsWithReadOnlyRoot = LocalFileSystem(
            listOf(VfsRoot("ro", "Read only", storage, VfsRootType.USB, writable = false)),
        )
        val error = runCatching { fsWithReadOnlyRoot.mkdir(VPath.of("/ro/new")) }.exceptionOrNull()
        assertEquals(VfsException.Reason.READ_ONLY, (error as VfsException).reason)
    }

    @Test
    fun `reports space for a root`() {
        assertTrue(fs.totalSpace(VPath.of("/data")) > 0)
        assertTrue(fs.freeSpace(VPath.of("/data")) >= 0)
    }

    @Test
    fun `localFile resolves inside the root and is null for the virtual root`() {
        assertNull(fs.localFile(VPath.ROOT))
        assertEquals(File(storage, "notes.txt").canonicalPath, fs.localFile(VPath.of("/data/notes.txt"))!!.canonicalPath)
    }

    @Test
    fun `setLastModified applies`() {
        val stamp = 1_600_000_000_000L
        assertTrue(fs.setLastModified(VPath.of("/data/notes.txt"), stamp))
        // Some filesystems round to whole seconds.
        assertEquals(stamp / 1000, fs.stat(VPath.of("/data/notes.txt"))!!.lastModified / 1000)
    }
}
