package ch.genedis.tvfileserver.core.vfs

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The single filesystem abstraction shared by the HTTP API, the WebDAV handler and the FTP
 * server, so path containment and read-only enforcement are implemented exactly once.
 *
 * Every method throws [VfsException] on failure rather than returning a sentinel, except
 * [stat] and [localFile], which return null for "absent".
 */
interface VirtualFileSystem {

    /** True when the whole filesystem refuses writes regardless of per-root permissions. */
    val readOnly: Boolean

    fun roots(): List<VfsRoot>

    /** Metadata for [path], or null when it does not exist. */
    fun stat(path: VPath): VfsEntry?

    /** Directory children, sorted directories-first then case-insensitively by name. */
    fun list(path: VPath): List<VfsEntry>

    /** The backing file, or null for the synthetic root. Used for fast length/range reads. */
    fun localFile(path: VPath): File?

    /** Opens a read stream, optionally skipping [offset] bytes (FTP `REST`, HTTP `Range`). */
    fun openRead(path: VPath, offset: Long = 0L): InputStream

    /** Opens a write stream, creating parent directories; truncates unless [append]. */
    fun openWrite(path: VPath, append: Boolean = false): OutputStream

    fun mkdir(path: VPath): VfsEntry

    fun delete(path: VPath, recursive: Boolean)

    fun move(source: VPath, target: VPath, overwrite: Boolean)

    fun copy(source: VPath, target: VPath, overwrite: Boolean, recursive: Boolean)

    fun setLastModified(path: VPath, epochMillis: Long): Boolean

    fun freeSpace(path: VPath): Long

    fun totalSpace(path: VPath): Long

    /** True when a write to [path] would be permitted. */
    fun isWritable(path: VPath): Boolean
}
