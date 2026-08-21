package ch.genedis.tvfileserver.core.web

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Supplies the files of the embedded web UI.
 *
 * The Android app backs this with `AssetManager`; tests use [InMemoryAssetSource].
 */
interface StaticAssetSource {

    /** Opens [relativePath] (never leading with `/`), or returns null when it is absent. */
    fun open(relativePath: String): InputStream?

    /** Byte length, or -1 when unknown (compressed assets cannot report one cheaply). */
    fun size(relativePath: String): Long

    fun exists(relativePath: String): Boolean
}

/** A source backed by a map, used by tests and by the WebDAV-only fallback page. */
class InMemoryAssetSource(private val files: Map<String, ByteArray>) : StaticAssetSource {

    override fun open(relativePath: String): InputStream? =
        files[relativePath]?.let { ByteArrayInputStream(it) }

    override fun size(relativePath: String): Long = files[relativePath]?.size?.toLong() ?: -1L

    override fun exists(relativePath: String): Boolean = files.containsKey(relativePath)
}
