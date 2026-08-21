package ch.genedis.tvfileserver.core.util

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Default transfer buffer size. 64 KiB is a good compromise on the low-end TV boxes this
 * server targets: large enough to keep the NIC busy, small enough that a dozen concurrent
 * connections do not put pressure on a 2 GB heap.
 */
const val DEFAULT_BUFFER_BYTES: Int = 64 * 1024

/**
 * Copies bytes from [input] to [output] using a caller-supplied [buffer].
 *
 * @param limit maximum number of bytes to copy; [Long.MAX_VALUE] means "until EOF".
 * @param onProgress invoked after every chunk with the *incremental* byte count.
 * @param isActive checked before every chunk; the copy returns early when it yields false.
 * @return the number of bytes actually copied.
 */
fun copyStream(
    input: InputStream,
    output: OutputStream,
    buffer: ByteArray,
    limit: Long = Long.MAX_VALUE,
    onProgress: ((Long) -> Unit)? = null,
    isActive: (() -> Boolean)? = null,
): Long {
    require(buffer.isNotEmpty()) { "buffer must not be empty" }
    var copied = 0L
    while (copied < limit) {
        if (isActive != null && !isActive()) break
        val want = minOf(buffer.size.toLong(), limit - copied).toInt()
        val read = input.read(buffer, 0, want)
        if (read < 0) break
        if (read == 0) continue
        output.write(buffer, 0, read)
        copied += read
        onProgress?.invoke(read.toLong())
    }
    return copied
}

/**
 * Skips exactly [count] bytes when possible.
 *
 * [InputStream.skip] is allowed to skip fewer bytes than requested, so this loops and falls
 * back to reading when skip stalls.
 *
 * @return the number of bytes actually skipped (less than [count] only at EOF).
 */
fun InputStream.skipFully(count: Long): Long {
    if (count <= 0) return 0
    var remaining = count
    val scratch = ByteArray(minOf(remaining, 8192L).toInt())
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
            continue
        }
        val read = read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
        if (read < 0) break
        remaining -= read
    }
    return count - remaining
}

/**
 * Reads until [length] bytes have been placed into [dest] or the stream ends.
 *
 * @return the number of bytes read; less than [length] only at EOF.
 */
fun InputStream.readFully(dest: ByteArray, offset: Int, length: Int): Int {
    var total = 0
    while (total < length) {
        val read = read(dest, offset + total, length - total)
        if (read < 0) break
        total += read
    }
    return total
}

/**
 * Reads the whole stream into memory, refusing to exceed [limit] bytes.
 *
 * @throws IOException when the stream holds more than [limit] bytes.
 */
fun InputStream.readAtMost(limit: Long): ByteArray {
    require(limit >= 0) { "limit must not be negative" }
    val out = ByteArrayOutputStream(minOf(limit, 16 * 1024L).toInt().coerceAtLeast(32))
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val read = read(buffer, 0, buffer.size)
        if (read < 0) break
        total += read
        if (total > limit) throw IOException("Payload exceeds the $limit byte limit")
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** Closes [closeable], swallowing any failure. Used on cleanup paths that must not throw. */
fun closeQuietly(closeable: Closeable?) {
    if (closeable == null) return
    try {
        closeable.close()
    } catch (error: Exception) {
        CoreLog.d("Io", "Ignoring close failure: ${error.message}")
    }
}
