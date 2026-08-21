package ch.genedis.tvfileserver.server

import android.content.res.AssetManager
import android.util.Log
import ch.genedis.tvfileserver.core.web.StaticAssetSource
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Serves the embedded web UI out of the APK's assets.
 *
 * Asset sizes are only knowable for files the packager left uncompressed, so [size] falls
 * back to -1 and the HTTP layer switches to chunked encoding for those.
 */
class AndroidAssetSource(
    private val assets: AssetManager,
    private val root: String = "web",
) : StaticAssetSource {

    override fun open(relativePath: String): InputStream? = try {
        assets.open(qualify(relativePath))
    } catch (error: FileNotFoundException) {
        null
    } catch (error: IOException) {
        Log.w(TAG, "Cannot open the asset '$relativePath'", error)
        null
    }

    override fun size(relativePath: String): Long = try {
        assets.openFd(qualify(relativePath)).use { it.length }
    } catch (error: IOException) {
        // Compressed assets have no file descriptor; that is expected, not an error.
        -1L
    }

    override fun exists(relativePath: String): Boolean {
        val stream = open(relativePath) ?: return false
        return try {
            stream.close()
            true
        } catch (error: IOException) {
            true
        }
    }

    private fun qualify(relativePath: String): String =
        if (root.isEmpty()) relativePath else "$root/$relativePath"

    private companion object {
        const val TAG = "AndroidAssetSource"
    }
}
