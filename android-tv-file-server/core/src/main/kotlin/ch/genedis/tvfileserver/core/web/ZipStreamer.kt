package ch.genedis.tvfileserver.core.web

import ch.genedis.tvfileserver.core.http.MimeTypes
import ch.genedis.tvfileserver.core.util.CoreLog
import ch.genedis.tvfileserver.core.util.copyStream
import ch.genedis.tvfileserver.core.vfs.VPath
import ch.genedis.tvfileserver.core.vfs.VfsEntry
import ch.genedis.tvfileserver.core.vfs.VfsException
import ch.genedis.tvfileserver.core.vfs.VirtualFileSystem
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streams a ZIP archive of a selection straight to the response.
 *
 * Nothing is staged on disk: a TV box rarely has room for a second copy of a media folder.
 * Already-compressed payloads are stored rather than deflated, which on a 1.5 GHz CPU is the
 * difference between saturating the LAN and being CPU-bound.
 */
class ZipStreamer(
    private val vfs: VirtualFileSystem,
    private val bufferSize: Int,
    private val hideDotFiles: Boolean,
    private val onBytes: (Long) -> Unit = {},
) {

    /**
     * Writes an archive of [paths] to [out].
     *
     * @param basePath the directory whose name is stripped from entry names, so extracting
     *   the archive does not recreate the whole tree from the storage root.
     */
    fun write(out: OutputStream, paths: List<VPath>, basePath: VPath) {
        val buffer = ByteArray(bufferSize)
        ZipOutputStream(out).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            for (path in paths) {
                val entry = try {
                    vfs.stat(path)
                } catch (error: VfsException) {
                    CoreLog.w(TAG, "Skipping ${path.value}: ${error.message}")
                    null
                } ?: continue
                val name = relativeName(path, basePath)
                if (entry.isDirectory) {
                    addDirectory(zip, path, name, buffer)
                } else {
                    addFile(zip, path, entry, name, buffer)
                }
            }
            zip.finish()
        }
    }

    private fun addDirectory(zip: ZipOutputStream, path: VPath, name: String, buffer: ByteArray) {
        val directoryEntry = ZipEntry("$name/").apply { time = safeTime(path) }
        try {
            zip.putNextEntry(directoryEntry)
            zip.closeEntry()
        } catch (error: IOException) {
            throw error
        }
        val children = try {
            vfs.list(path)
        } catch (error: VfsException) {
            CoreLog.w(TAG, "Cannot list ${path.value}: ${error.message}")
            return
        }
        for (child in children) {
            if (hideDotFiles && child.isHidden) continue
            val childName = "$name/${child.name}"
            if (child.isDirectory) {
                addDirectory(zip, child.path, childName, buffer)
            } else {
                addFile(zip, child.path, child, childName, buffer)
            }
        }
    }

    private fun addFile(
        zip: ZipOutputStream,
        path: VPath,
        entry: VfsEntry,
        name: String,
        buffer: ByteArray,
    ) {
        if (!entry.readable) {
            CoreLog.d(TAG, "Skipping unreadable ${path.value}")
            return
        }
        val stored = MimeTypes.isPrecompressed(entry.name) && entry.size in 0..STORED_SIZE_LIMIT
        val zipEntry = ZipEntry(name).apply { time = entry.lastModified }

        if (stored) {
            // A STORED entry must carry its size and CRC up front, so the file is read twice.
            // Worth it below the size limit: no deflate pass at all on media files.
            val crc = try {
                crcOf(path, buffer)
            } catch (error: VfsException) {
                CoreLog.w(TAG, "Cannot checksum ${path.value}: ${error.message}")
                return
            }
            zipEntry.method = ZipEntry.STORED
            zipEntry.size = entry.size
            zipEntry.compressedSize = entry.size
            zipEntry.crc = crc
        } else {
            zipEntry.method = ZipEntry.DEFLATED
        }

        zip.putNextEntry(zipEntry)
        try {
            vfs.openRead(path).use { input ->
                copyStream(input, zip, buffer, onProgress = onBytes)
            }
        } catch (error: VfsException) {
            CoreLog.w(TAG, "Cannot read ${path.value} while zipping: ${error.message}")
        }
        zip.closeEntry()
    }

    private fun crcOf(path: VPath, buffer: ByteArray): Long {
        val crc = CRC32()
        vfs.openRead(path).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private fun safeTime(path: VPath): Long = vfs.stat(path)?.lastModified ?: System.currentTimeMillis()

    private fun relativeName(path: VPath, basePath: VPath): String {
        if (basePath.isRoot) return path.value.trimStart('/')
        val prefix = basePath.value + "/"
        return if (path.value.startsWith(prefix)) path.value.substring(prefix.length) else path.name
    }

    private companion object {
        const val TAG = "ZipStreamer"

        /**
         * Above this size the two-pass CRC costs more than deflating would; large media files
         * fall back to DEFLATED, which needs no pre-pass because the ZIP data descriptor
         * carries the sizes.
         */
        const val STORED_SIZE_LIMIT = 64L * 1024 * 1024
    }
}
