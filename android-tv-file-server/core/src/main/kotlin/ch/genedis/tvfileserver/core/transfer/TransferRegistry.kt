package ch.genedis.tvfileserver.core.transfer

import ch.genedis.tvfileserver.core.util.CoreLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class TransferDirection { UPLOAD, DOWNLOAD }

enum class TransferProtocol { HTTP, WEBDAV, FTP }

/** An immutable snapshot of one transfer, safe to hand to the UI layer. */
data class TransferInfo(
    val id: Long,
    val name: String,
    val path: String,
    val direction: TransferDirection,
    val protocol: TransferProtocol,
    val client: String,
    val transferred: Long,
    val total: Long,
    val startedAtMillis: Long,
    val bytesPerSecond: Long,
    val finished: Boolean,
    val error: String?,
) {
    /** Completion ratio in `0..1`, or null when the total size is unknown. */
    val progress: Float?
        get() = if (total > 0) (transferred.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null
}

/** Aggregate counters across all transfers seen since the registry was created. */
data class TransferTotals(
    val activeCount: Int,
    val bytesUploaded: Long,
    val bytesDownloaded: Long,
    val uploadBps: Long,
    val downloadBps: Long,
)

/** Handle held by whichever protocol is moving the bytes. */
interface TransferHandle : Closeable {
    val id: Long
    fun advance(bytes: Long)
    fun complete()
    fun fail(error: Throwable?)
    override fun close()
}

/**
 * Tracks in-flight transfers so the TV screen, the notification and the web UI can show
 * progress for every protocol.
 *
 * Progress reporting is deliberately cheap: [TransferHandle.advance] only bumps two atomics
 * and republishes the [StateFlow] at most every [PUBLISH_INTERVAL_MS] per transfer. Without
 * that coalescing a fast LAN copy would emit tens of thousands of UI updates per second on a
 * device that can barely render sixty.
 */
class TransferRegistry(private val historyLimit: Int = 20) {

    private val ids = AtomicLong(0)
    private val entries = ConcurrentHashMap<Long, Entry>()
    private val finishedHistory = ArrayDeque<TransferInfo>()
    private val historyLock = Any()

    private val cumulativeUploaded = AtomicLong(0)
    private val cumulativeDownloaded = AtomicLong(0)

    private val _active = MutableStateFlow<List<TransferInfo>>(emptyList())
    private val _recent = MutableStateFlow<List<TransferInfo>>(emptyList())
    private val _totals = MutableStateFlow(TransferTotals(0, 0, 0, 0, 0))

    val active: StateFlow<List<TransferInfo>> = _active.asStateFlow()
    val recent: StateFlow<List<TransferInfo>> = _recent.asStateFlow()
    val totals: StateFlow<TransferTotals> = _totals.asStateFlow()

    fun begin(
        name: String,
        path: String,
        direction: TransferDirection,
        protocol: TransferProtocol,
        client: String,
        total: Long,
    ): TransferHandle {
        val id = ids.incrementAndGet()
        val entry = Entry(id, name, path, direction, protocol, client, total)
        entries[id] = entry
        publish()
        return entry
    }

    /** Current state of every in-flight transfer. */
    fun snapshot(): List<TransferInfo> = entries.values
        .map { it.toInfo() }
        .sortedBy { it.startedAtMillis }

    /** Drops history and cumulative counters. In-flight transfers are left alone. */
    fun reset() {
        synchronized(historyLock) {
            finishedHistory.clear()
            _recent.value = emptyList()
        }
        cumulativeUploaded.set(0)
        cumulativeDownloaded.set(0)
        publish()
    }

    private fun publish() {
        val snapshot = snapshot()
        _active.value = snapshot
        val uploadBps = snapshot.filter { it.direction == TransferDirection.UPLOAD }.sumOf { it.bytesPerSecond }
        val downloadBps = snapshot.filter { it.direction == TransferDirection.DOWNLOAD }.sumOf { it.bytesPerSecond }
        _totals.value = TransferTotals(
            activeCount = snapshot.size,
            bytesUploaded = cumulativeUploaded.get(),
            bytesDownloaded = cumulativeDownloaded.get(),
            uploadBps = uploadBps,
            downloadBps = downloadBps,
        )
    }

    private fun finish(entry: Entry) {
        entries.remove(entry.id)
        val info = entry.toInfo()
        synchronized(historyLock) {
            finishedHistory.addFirst(info)
            while (finishedHistory.size > historyLimit) finishedHistory.removeLast()
            _recent.value = finishedHistory.toList()
        }
        publish()
    }

    private inner class Entry(
        override val id: Long,
        val name: String,
        val path: String,
        val direction: TransferDirection,
        val protocol: TransferProtocol,
        val client: String,
        val total: Long,
    ) : TransferHandle {

        private val transferred = AtomicLong(0)
        private val startedAtMillis = System.currentTimeMillis()
        private val startedAtNanos = System.nanoTime()
        private val closed = AtomicBoolean(false)

        @Volatile private var lastPublishNanos = startedAtNanos
        @Volatile private var lastPublishBytes = 0L
        @Volatile private var smoothedBps = 0L
        @Volatile private var finished = false
        @Volatile private var error: String? = null

        override fun advance(bytes: Long) {
            if (bytes <= 0) return
            val now = transferred.addAndGet(bytes)
            when (direction) {
                TransferDirection.UPLOAD -> cumulativeUploaded.addAndGet(bytes)
                TransferDirection.DOWNLOAD -> cumulativeDownloaded.addAndGet(bytes)
            }
            val nanos = System.nanoTime()
            if (nanos - lastPublishNanos < PUBLISH_INTERVAL_NANOS) return
            recomputeRate(nanos, now)
            publish()
        }

        override fun complete() {
            if (!closed.compareAndSet(false, true)) return
            finished = true
            smoothedBps = averageRate()
            finish(this)
        }

        override fun fail(error: Throwable?) {
            if (!closed.compareAndSet(false, true)) return
            finished = true
            this.error = error?.message ?: error?.javaClass?.simpleName ?: "failed"
            smoothedBps = 0
            CoreLog.d(TAG, "Transfer $id ($name) failed: ${this.error}")
            finish(this)
        }

        override fun close() = complete()

        fun toInfo(): TransferInfo = TransferInfo(
            id = id,
            name = name,
            path = path,
            direction = direction,
            protocol = protocol,
            client = client,
            transferred = transferred.get(),
            total = total,
            startedAtMillis = startedAtMillis,
            bytesPerSecond = smoothedBps,
            finished = finished,
            error = error,
        )

        /** Exponentially weighted moving average so the displayed speed does not jitter. */
        private fun recomputeRate(nowNanos: Long, totalBytes: Long) {
            val elapsedNanos = nowNanos - lastPublishNanos
            if (elapsedNanos <= 0) return
            val deltaBytes = totalBytes - lastPublishBytes
            val instantBps = (deltaBytes * 1_000_000_000.0 / elapsedNanos).toLong()
            smoothedBps = if (smoothedBps == 0L) {
                instantBps
            } else {
                (RATE_ALPHA * instantBps + (1 - RATE_ALPHA) * smoothedBps).toLong()
            }
            lastPublishNanos = nowNanos
            lastPublishBytes = totalBytes
        }

        private fun averageRate(): Long {
            val elapsedNanos = System.nanoTime() - startedAtNanos
            if (elapsedNanos <= 0) return 0
            return (transferred.get() * 1_000_000_000.0 / elapsedNanos).toLong()
        }
    }

    private companion object {
        const val TAG = "TransferRegistry"
        const val PUBLISH_INTERVAL_MS = 250L
        const val PUBLISH_INTERVAL_NANOS = PUBLISH_INTERVAL_MS * 1_000_000L
        const val RATE_ALPHA = 0.3
    }
}
