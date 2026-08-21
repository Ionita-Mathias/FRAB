package ch.genedis.tvfileserver.core.vfs

import ch.genedis.tvfileserver.core.http.MimeTypes
import ch.genedis.tvfileserver.core.util.CoreLog
import ch.genedis.tvfileserver.core.util.DEFAULT_BUFFER_BYTES
import ch.genedis.tvfileserver.core.util.copyStream
import ch.genedis.tvfileserver.core.util.skipFully
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [VirtualFileSystem] backed by real directories on the device.
 *
 * ## Containment
 *
 * Every resolved path is canonicalised and checked against the canonical path of its root
 * before any operation touches it. That single check defeats `..` traversal, absolute
 * segments and symlinks pointing outside the exposed area. Files that do not exist yet
 * cannot be canonicalised directly, so their *parent* is canonicalised and the name is
 * re-appended.
 */
class LocalFileSystem(
    roots: List<VfsRoot>,
    override val readOnly: Boolean = false,
    private val hideDotFiles: Boolean = true,
) : VirtualFileSystem {

    private class Mount(val root: VfsRoot, val canonicalPath: String)

    private val mounts: List<Mount> = roots.map { root ->
        Mount(root, canonicalOf(root.directory))
    }
    private val mountsById: Map<String, Mount> = mounts.associateBy { it.root.id }

    override fun roots(): List<VfsRoot> = mounts.map { it.root }

    override fun stat(path: VPath): VfsEntry? {
        if (path.isRoot) {
            return VfsEntry(
                path = VPath.ROOT,
                name = "",
                isDirectory = true,
                size = 0,
                lastModified = 0,
                readable = true,
                writable = !readOnly && mounts.any { it.root.writable },
                isHidden = false,
                mimeType = MimeTypes.DIRECTORY,
            )
        }
        val resolved = resolveOrNull(path) ?: return null
        if (resolved.isMountItself) return mountEntry(resolved.mount)
        if (!resolved.file.exists()) return null
        return entryOf(resolved.mount, path, resolved.file)
    }

    override fun list(path: VPath): List<VfsEntry> {
        if (path.isRoot) return mounts.map { mountEntry(it) }
        val resolved = resolve(path)
        val file = resolved.file
        if (!file.exists()) throw VfsException.notFound(path)
        if (!file.isDirectory) throw VfsException(VfsException.Reason.NOT_A_DIRECTORY, "Not a directory: $path")
        val children = file.listFiles()
            ?: throw VfsException(VfsException.Reason.ACCESS_DENIED, "Cannot list: $path")
        return children
            .map { child -> entryOf(resolved.mount, path.child(child.name), child) }
            .sortedWith(ENTRY_ORDER)
    }

    override fun localFile(path: VPath): File? {
        if (path.isRoot) return null
        val resolved = resolveOrNull(path) ?: return null
        return resolved.file
    }

    override fun openRead(path: VPath, offset: Long): InputStream {
        val resolved = resolve(path)
        val file = resolved.file
        if (!file.exists()) throw VfsException.notFound(path)
        if (file.isDirectory) throw VfsException(VfsException.Reason.IS_A_DIRECTORY, "Is a directory: $path")
        val stream = try {
            FileInputStream(file)
        } catch (error: IOException) {
            throw VfsException(VfsException.Reason.ACCESS_DENIED, "Cannot read $path", error)
        }
        if (offset > 0) {
            val skipped = stream.skipFully(offset)
            if (skipped < offset) {
                stream.close()
                throw VfsException(VfsException.Reason.IO_ERROR, "Offset $offset is past the end of $path")
            }
        }
        return stream
    }

    override fun openWrite(path: VPath, append: Boolean): OutputStream {
        val resolved = requireWritable(path)
        val file = resolved.file
        if (file.isDirectory) throw VfsException(VfsException.Reason.IS_A_DIRECTORY, "Is a directory: $path")
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw VfsException(VfsException.Reason.IO_ERROR, "Cannot create parent directory of $path")
        }
        return try {
            FileOutputStream(file, append)
        } catch (error: IOException) {
            throw translateWriteFailure(path, error)
        }
    }

    override fun mkdir(path: VPath): VfsEntry {
        if (path.isRoot) throw VfsException(VfsException.Reason.ALREADY_EXISTS, "Root already exists")
        val resolved = requireWritable(path)
        val file = resolved.file
        if (file.exists()) {
            throw VfsException(VfsException.Reason.ALREADY_EXISTS, "Already exists: $path")
        }
        if (!file.mkdirs()) {
            throw VfsException(VfsException.Reason.IO_ERROR, "Cannot create directory: $path")
        }
        return entryOf(resolved.mount, path, file)
    }

    override fun delete(path: VPath, recursive: Boolean) {
        if (path.isRoot) throw VfsException.accessDenied(path)
        val resolved = requireWritable(path)
        if (resolved.isMountItself) throw VfsException.accessDenied(path)
        val file = resolved.file
        if (!file.exists()) throw VfsException.notFound(path)
        if (file.isDirectory) {
            val children = file.list()
            if (!children.isNullOrEmpty() && !recursive) {
                throw VfsException(VfsException.Reason.CONFLICT, "Directory is not empty: $path")
            }
            if (!deleteRecursively(file)) {
                throw VfsException(VfsException.Reason.IO_ERROR, "Cannot delete: $path")
            }
        } else if (!file.delete()) {
            throw VfsException(VfsException.Reason.IO_ERROR, "Cannot delete: $path")
        }
    }

    override fun move(source: VPath, target: VPath, overwrite: Boolean) {
        val from = requireWritable(source)
        val to = requireWritable(target)
        if (!from.file.exists()) throw VfsException.notFound(source)
        if (from.isMountItself || to.isMountItself) throw VfsException.accessDenied(source)
        if (target.startsWith(source) && target != source) {
            throw VfsException(VfsException.Reason.CONFLICT, "Cannot move $source into itself")
        }
        prepareTarget(to.file, target, overwrite)
        if (from.file.renameTo(to.file)) return

        // Cross-volume rename is not supported by the kernel: fall back to copy + delete.
        CoreLog.d(TAG, "renameTo failed for $source -> $target, falling back to copy")
        copyTree(from.file, to.file, source, target)
        if (!deleteRecursively(from.file)) {
            throw VfsException(VfsException.Reason.IO_ERROR, "Copied but could not remove the source: $source")
        }
    }

    override fun copy(source: VPath, target: VPath, overwrite: Boolean, recursive: Boolean) {
        val from = resolve(source)
        val to = requireWritable(target)
        if (!from.file.exists()) throw VfsException.notFound(source)
        if (from.file.isDirectory && !recursive) {
            throw VfsException(VfsException.Reason.IS_A_DIRECTORY, "Directory copy requires recursion: $source")
        }
        if (target.startsWith(source) && target != source) {
            throw VfsException(VfsException.Reason.CONFLICT, "Cannot copy $source into itself")
        }
        prepareTarget(to.file, target, overwrite)
        copyTree(from.file, to.file, source, target)
    }

    override fun setLastModified(path: VPath, epochMillis: Long): Boolean {
        if (readOnly) return false
        val resolved = resolveOrNull(path) ?: return false
        if (!resolved.file.exists()) return false
        return try {
            resolved.file.setLastModified(epochMillis)
        } catch (error: SecurityException) {
            CoreLog.d(TAG, "setLastModified denied for $path: ${error.message}")
            false
        }
    }

    override fun freeSpace(path: VPath): Long = spaceOf(path) { it.usableSpace }

    override fun totalSpace(path: VPath): Long = spaceOf(path) { it.totalSpace }

    override fun isWritable(path: VPath): Boolean {
        if (readOnly) return false
        if (path.isRoot) return false
        val resolved = resolveOrNull(path) ?: return false
        return resolved.mount.root.writable
    }

    // ------------------------------------------------------------------ internals

    private class Resolved(val mount: Mount, val file: File, val isMountItself: Boolean)

    private fun resolve(path: VPath): Resolved =
        resolveOrNull(path) ?: throw VfsException.notFound(path)

    /**
     * Maps a virtual path onto a real file, enforcing containment.
     *
     * @return null when the first segment names no known root.
     * @throws VfsException with [VfsException.Reason.ACCESS_DENIED] when the resolved file
     *   would fall outside its root.
     */
    private fun resolveOrNull(path: VPath): Resolved? {
        if (path.isRoot) return null
        val segments = path.segments
        val mount = mountsById[segments[0]] ?: return null
        if (segments.size == 1) return Resolved(mount, mount.root.directory, isMountItself = true)

        var file = mount.root.directory
        for (index in 1 until segments.size) {
            val segment = segments[index]
            if (!VPath.isValidSegment(segment)) throw VfsException.invalidPath(path.value)
            file = File(file, segment)
        }
        verifyContained(mount, file, path)
        return Resolved(mount, file, isMountItself = false)
    }

    /**
     * Rejects any file whose canonical location escapes its root.
     *
     * For a file that does not exist yet, the deepest existing ancestor is canonicalised and
     * the remaining names are appended, so a symlinked parent cannot smuggle the target out.
     */
    private fun verifyContained(mount: Mount, file: File, path: VPath) {
        val canonical = try {
            canonicalOfPossiblyMissing(file)
        } catch (error: IOException) {
            throw VfsException(VfsException.Reason.IO_ERROR, "Cannot resolve $path", error)
        }
        val rootPath = mount.canonicalPath
        val contained = canonical == rootPath || canonical.startsWith(rootPath + File.separator)
        if (!contained) {
            CoreLog.w(TAG, "Blocked path escape: $path resolved to $canonical outside $rootPath")
            throw VfsException.accessDenied(path)
        }
    }

    private fun requireWritable(path: VPath): Resolved {
        if (readOnly) throw VfsException.readOnly(path)
        val resolved = resolve(path)
        if (!resolved.mount.root.writable) throw VfsException.readOnly(path)
        return resolved
    }

    private fun prepareTarget(target: File, targetPath: VPath, overwrite: Boolean) {
        if (target.exists()) {
            if (!overwrite) throw VfsException(VfsException.Reason.ALREADY_EXISTS, "Already exists: $targetPath")
            if (!deleteRecursively(target)) {
                throw VfsException(VfsException.Reason.IO_ERROR, "Cannot replace: $targetPath")
            }
        }
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw VfsException(VfsException.Reason.CONFLICT, "Parent directory is missing: ${targetPath.parent}")
        }
    }

    private fun copyTree(source: File, target: File, sourcePath: VPath, targetPath: VPath) {
        if (source.isDirectory) {
            if (!target.exists() && !target.mkdirs()) {
                throw VfsException(VfsException.Reason.IO_ERROR, "Cannot create $targetPath")
            }
            val children = source.listFiles() ?: return
            for (child in children) {
                copyTree(child, File(target, child.name), sourcePath, targetPath)
            }
            target.setLastModified(source.lastModified())
            return
        }
        val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output ->
                    copyStream(input, output, buffer)
                }
            }
        } catch (error: IOException) {
            throw translateWriteFailure(targetPath, error)
        }
        target.setLastModified(source.lastModified())
    }

    /**
     * Deletes [file] and, for directories, its contents.
     *
     * Symlinked directories are unlinked rather than descended into, so a link loop or a link
     * pointing at the real storage root cannot cause runaway deletion.
     */
    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory && !isSymlink(file)) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    if (!deleteRecursively(child)) return false
                }
            }
        }
        return file.delete() || !file.exists()
    }

    private fun isSymlink(file: File): Boolean {
        return try {
            val canonicalParent = file.parentFile?.canonicalFile ?: return false
            val probe = File(canonicalParent, file.name)
            probe.canonicalFile != probe.absoluteFile
        } catch (error: IOException) {
            CoreLog.d(TAG, "Symlink probe failed for ${file.path}: ${error.message}")
            // Treat an unreadable entry as a link: refusing to descend is the safe default.
            true
        }
    }

    private fun mountEntry(mount: Mount): VfsEntry = VfsEntry(
        path = VPath.ROOT.child(mount.root.id),
        name = mount.root.displayName,
        isDirectory = true,
        size = 0,
        lastModified = safeLastModified(mount.root.directory),
        readable = true,
        writable = !readOnly && mount.root.writable,
        isHidden = false,
        mimeType = MimeTypes.DIRECTORY,
        rootId = mount.root.id,
    )

    private fun entryOf(mount: Mount, path: VPath, file: File): VfsEntry {
        val isDirectory = file.isDirectory
        return VfsEntry(
            path = path,
            name = file.name,
            isDirectory = isDirectory,
            size = if (isDirectory) 0 else safeLength(file),
            lastModified = safeLastModified(file),
            readable = safeCanRead(file),
            writable = !readOnly && mount.root.writable && safeCanWrite(file),
            isHidden = hideDotFiles && isHiddenName(file.name),
            mimeType = if (isDirectory) MimeTypes.DIRECTORY else MimeTypes.forFileName(file.name),
        )
    }

    private fun isHiddenName(name: String): Boolean = name.startsWith(".") || name == "lost+found"

    private inline fun spaceOf(path: VPath, selector: (File) -> Long): Long {
        val directory = if (path.isRoot) {
            mounts.firstOrNull()?.root?.directory ?: return 0
        } else {
            resolveOrNull(path)?.mount?.root?.directory ?: return 0
        }
        return try {
            selector(directory)
        } catch (error: SecurityException) {
            CoreLog.d(TAG, "Space query denied for $path: ${error.message}")
            0
        }
    }

    private fun translateWriteFailure(path: VPath, error: IOException): VfsException {
        val message = error.message.orEmpty().lowercase()
        val reason = when {
            message.contains("no space") || message.contains("enospc") -> VfsException.Reason.NO_SPACE
            message.contains("permission denied") || message.contains("eacces") -> VfsException.Reason.ACCESS_DENIED
            message.contains("read-only") -> VfsException.Reason.READ_ONLY
            else -> VfsException.Reason.IO_ERROR
        }
        return VfsException(reason, "Cannot write $path: ${error.message}", error)
    }

    private companion object {
        const val TAG = "LocalFileSystem"

        val ENTRY_ORDER: Comparator<VfsEntry> = Comparator { a, b ->
            when {
                a.isDirectory && !b.isDirectory -> -1
                !a.isDirectory && b.isDirectory -> 1
                else -> {
                    val byName = String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name)
                    if (byName != 0) byName else a.name.compareTo(b.name)
                }
            }
        }

        fun canonicalOf(file: File): String = try {
            file.canonicalPath
        } catch (error: IOException) {
            CoreLog.w("LocalFileSystem", "Cannot canonicalise ${file.path}", error)
            file.absolutePath
        }

        /**
         * Canonical path of a file that may not exist: walks up to the deepest existing
         * ancestor, canonicalises that, and re-appends the missing names.
         */
        fun canonicalOfPossiblyMissing(file: File): String {
            var existing: File? = file
            val missing = ArrayList<String>(4)
            while (existing != null && !existing.exists()) {
                missing.add(existing.name)
                existing = existing.parentFile
            }
            if (existing == null) return file.absolutePath
            var canonical = existing.canonicalFile
            for (index in missing.indices.reversed()) {
                canonical = File(canonical, missing[index])
            }
            return canonical.path
        }

        fun safeLength(file: File): Long = try {
            file.length()
        } catch (error: SecurityException) {
            0
        }

        fun safeLastModified(file: File): Long = try {
            file.lastModified()
        } catch (error: SecurityException) {
            0
        }

        fun safeCanRead(file: File): Boolean = try {
            file.canRead()
        } catch (error: SecurityException) {
            false
        }

        fun safeCanWrite(file: File): Boolean = try {
            file.canWrite()
        } catch (error: SecurityException) {
            false
        }
    }
}
