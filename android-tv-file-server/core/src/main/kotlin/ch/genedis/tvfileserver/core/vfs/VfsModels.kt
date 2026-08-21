package ch.genedis.tvfileserver.core.vfs

import java.io.File
import java.io.IOException

/** How a storage root is attached to the device. Purely informational, used by the UI. */
enum class VfsRootType { INTERNAL, SD_CARD, USB, APP_PRIVATE, CUSTOM }

/**
 * One exposed storage area.
 *
 * @param id URL-safe, stable, and unique; it becomes the first segment of every [VPath]
 *   inside this root, so renaming it invalidates bookmarks.
 */
data class VfsRoot(
    val id: String,
    val displayName: String,
    val directory: File,
    val type: VfsRootType,
    val writable: Boolean,
)

/** A file or directory as seen through the virtual filesystem. */
data class VfsEntry(
    val path: VPath,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val readable: Boolean,
    val writable: Boolean,
    val isHidden: Boolean,
    val mimeType: String,
    val rootId: String? = null,
)

/** A filesystem failure carrying enough context to be mapped onto an HTTP or FTP status. */
class VfsException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    enum class Reason {
        NOT_FOUND,
        NOT_A_DIRECTORY,
        IS_A_DIRECTORY,
        ALREADY_EXISTS,
        READ_ONLY,
        ACCESS_DENIED,
        INVALID_PATH,
        NO_SPACE,
        IO_ERROR,
        CONFLICT,
    }

    companion object {
        fun notFound(path: VPath) = VfsException(Reason.NOT_FOUND, "No such file or directory: $path")
        fun accessDenied(path: VPath) = VfsException(Reason.ACCESS_DENIED, "Access denied: $path")
        fun readOnly(path: VPath) = VfsException(Reason.READ_ONLY, "Read-only location: $path")
        fun invalidPath(raw: String) = VfsException(Reason.INVALID_PATH, "Invalid path: '$raw'")
    }
}
