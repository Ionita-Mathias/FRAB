package ch.genedis.tvfileserver.storage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import ch.genedis.tvfileserver.core.vfs.VfsRoot
import ch.genedis.tvfileserver.core.vfs.VfsRootType
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Discovers the storage areas to expose and the permissions needed to reach them.
 *
 * TV boxes are inconsistent here: the Mi Box 3 mounts USB sticks under `/storage/XXXX-XXXX`
 * but only some builds report them through `StorageManager`, and `StorageVolume.getPath()`
 * stayed hidden until API 30. Every path below is therefore defensive and the result is
 * de-duplicated by canonical path.
 */
object AndroidStorage {

    private const val TAG = "AndroidStorage"

    /**
     * The storage roots to serve, in the order the UI should show them.
     *
     * @param includeAppPrivate also exposes the app-private external directory, which needs
     *   no permission at all and is the fallback when all-files access is denied.
     */
    fun discoverRoots(context: Context, includeAppPrivate: Boolean): List<VfsRoot> {
        val roots = LinkedHashMap<String, VfsRoot>()

        addRoot(roots, primaryRoot())
        for (volume in removableVolumes(context)) addRoot(roots, volume)
        for (volume in externalFilesFallback(context)) addRoot(roots, volume)

        if (includeAppPrivate || roots.isEmpty()) {
            addRoot(roots, appPrivateRoot(context))
        }

        if (roots.isEmpty()) {
            // Should be unreachable: getFilesDir always exists. Kept so the server can still
            // start and tell the user something is wrong, rather than failing to bind.
            Log.w(TAG, "No storage root discovered, falling back to the internal app directory")
            addRoot(
                roots,
                VfsRoot("app", "App storage", context.filesDir, VfsRootType.APP_PRIVATE, writable = true),
            )
        }
        return roots.values.toList()
    }

    /** True when the app holds Android 11+ all-files access. */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** True when the pre-Android-11 read permission has been granted. */
    fun hasLegacyStoragePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

    /** True when the app can read shared storage by whichever route this API level uses. */
    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasAllFilesAccess()
        } else {
            hasLegacyStoragePermission(context)
        }

    /** Runtime permissions worth requesting on this API level. */
    fun requiredRuntimePermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_AUDIO,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
        )
        else -> arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
    }

    /**
     * An intent that opens the all-files-access screen, or null when this device has none.
     *
     * Many TV builds ship without the Settings activity that handles it, so the caller must
     * be ready for null and for [android.content.ActivityNotFoundException] anyway.
     */
    fun manageAllFilesIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val perApp = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        if (resolves(context, perApp)) return perApp

        val global = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        if (resolves(context, global)) return global

        Log.w(TAG, "This device exposes no all-files-access settings screen")
        return null
    }

    // ------------------------------------------------------------------ internals

    private fun resolves(context: Context, intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null

    private fun primaryRoot(): VfsRoot? {
        val directory = try {
            Environment.getExternalStorageDirectory()
        } catch (error: Exception) {
            Log.w(TAG, "Cannot read the primary storage directory", error)
            null
        } ?: return null
        if (!directory.isDirectory || !directory.canRead()) return null
        return VfsRoot(
            id = "internal",
            displayName = "Internal storage",
            directory = directory,
            type = VfsRootType.INTERNAL,
            writable = directory.canWrite(),
        )
    }

    /** Volumes reported by [StorageManager], i.e. SD cards and USB sticks. */
    private fun removableVolumes(context: Context): List<VfsRoot> {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return emptyList()
        val volumes = try {
            manager.storageVolumes
        } catch (error: Exception) {
            Log.w(TAG, "Cannot enumerate storage volumes", error)
            return emptyList()
        }

        var usbIndex = 0
        var cardIndex = 0
        val result = ArrayList<VfsRoot>(volumes.size)
        for (volume in volumes) {
            if (volume.isPrimary) continue
            val state = try {
                volume.state
            } catch (error: Exception) {
                null
            }
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue

            val directory = volumeDirectory(volume) ?: continue
            if (!directory.isDirectory || !directory.canRead()) continue

            val looksLikeUsb = directory.absolutePath.lowercase(Locale.ROOT).contains("usb") ||
                (volume.isRemovable && volume.getDescription(context)?.lowercase(Locale.ROOT)?.contains("usb") == true)
            val id = if (looksLikeUsb) "usb${++usbIndex}" else "sdcard${if (++cardIndex == 1) "" else cardIndex.toString()}"

            result.add(
                VfsRoot(
                    id = id,
                    displayName = volume.getDescription(context) ?: if (looksLikeUsb) "USB storage" else "SD card",
                    directory = directory,
                    type = if (looksLikeUsb) VfsRootType.USB else VfsRootType.SD_CARD,
                    writable = state == Environment.MEDIA_MOUNTED && directory.canWrite(),
                ),
            )
        }
        return result
    }

    /**
     * The mount point of [volume].
     *
     * `StorageVolume.getDirectory()` only exists from API 30. Below that the path is only
     * reachable through a hidden `getPath()` method, so it is called reflectively and any
     * failure simply drops the volume rather than crashing the discovery pass.
     */
    private fun volumeDirectory(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return volume.directory
        }
        return try {
            val path = StorageVolume::class.java.getMethod("getPath").invoke(volume) as? String
            path?.let { File(it) }
        } catch (error: Exception) {
            Log.d(TAG, "getPath() is unavailable on this build: ${error.message}")
            null
        }
    }

    /**
     * Secondary volumes derived from the app-specific external directories.
     *
     * `getExternalFilesDirs` returns `<volume>/Android/data/<pkg>/files`, so walking four
     * levels up lands on the volume root. Used when [StorageManager] under-reports, which
     * happens on several TV builds.
     */
    private fun externalFilesFallback(context: Context): List<VfsRoot> {
        val dirs = try {
            ContextCompat.getExternalFilesDirs(context, null)
        } catch (error: Exception) {
            Log.w(TAG, "Cannot list the external files directories", error)
            return emptyList()
        }
        val result = ArrayList<VfsRoot>(2)
        var index = 0
        for (dir in dirs) {
            if (dir == null) continue
            var candidate: File? = dir
            repeat(4) { candidate = candidate?.parentFile }
            val volumeRoot = candidate ?: continue
            if (!volumeRoot.isDirectory || !volumeRoot.canRead()) continue
            if (volumeRoot.absolutePath == Environment.getExternalStorageDirectory()?.absolutePath) continue

            index++
            val looksLikeUsb = volumeRoot.absolutePath.lowercase(Locale.ROOT).contains("usb")
            result.add(
                VfsRoot(
                    id = if (looksLikeUsb) "usbx$index" else "external$index",
                    displayName = if (looksLikeUsb) "USB storage" else "External storage",
                    directory = volumeRoot,
                    type = if (looksLikeUsb) VfsRootType.USB else VfsRootType.SD_CARD,
                    writable = volumeRoot.canWrite(),
                ),
            )
        }
        return result
    }

    private fun appPrivateRoot(context: Context): VfsRoot? {
        val directory = ContextCompat.getExternalFilesDirs(context, null).firstOrNull() ?: context.filesDir
        if (!directory.isDirectory && !directory.mkdirs()) return null
        return VfsRoot(
            id = "app",
            displayName = "App storage (no permission needed)",
            directory = directory,
            type = VfsRootType.APP_PRIVATE,
            writable = directory.canWrite(),
        )
    }

    /** Adds [root] unless another root already points at the same canonical directory. */
    private fun addRoot(target: MutableMap<String, VfsRoot>, root: VfsRoot?) {
        if (root == null) return
        val key = try {
            root.directory.canonicalPath
        } catch (error: IOException) {
            root.directory.absolutePath
        }
        if (target.values.any { canonicalOf(it.directory) == key }) return
        target[root.id] = root
    }

    private fun canonicalOf(file: File): String = try {
        file.canonicalPath
    } catch (error: IOException) {
        file.absolutePath
    }
}
