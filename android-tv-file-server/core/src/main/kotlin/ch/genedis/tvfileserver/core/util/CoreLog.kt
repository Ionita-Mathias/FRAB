package ch.genedis.tvfileserver.core.util

/**
 * Logging abstraction for the core module.
 *
 * The core module cannot depend on `android.util.Log`, so hosts install their own
 * implementation through [CoreLog.logger]. Tests use [StdoutCoreLogger].
 */
interface CoreLogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)
    fun e(tag: String, message: String, error: Throwable? = null)
}

/** Discards everything. The default so that a host which forgets to install a logger is silent. */
object NoopCoreLogger : CoreLogger {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, error: Throwable?) = Unit
    override fun e(tag: String, message: String, error: Throwable?) = Unit
}

/** Prints to stdout/stderr. Intended for unit tests and desktop experiments. */
class StdoutCoreLogger(private val minLevel: Level = Level.DEBUG) : CoreLogger {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    override fun d(tag: String, message: String) = log(Level.DEBUG, tag, message, null)
    override fun i(tag: String, message: String) = log(Level.INFO, tag, message, null)
    override fun w(tag: String, message: String, error: Throwable?) = log(Level.WARN, tag, message, error)
    override fun e(tag: String, message: String, error: Throwable?) = log(Level.ERROR, tag, message, error)

    private fun log(level: Level, tag: String, message: String, error: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        val stream = if (level.ordinal >= Level.WARN.ordinal) System.err else System.out
        stream.println("${level.name.first()}/$tag: $message")
        if (error != null) {
            stream.println("    ${error.javaClass.name}: ${error.message}")
            for (frame in error.stackTrace.take(8)) stream.println("        at $frame")
        }
        stream.flush()
    }
}

/** Global logging entry point used across the core module. */
object CoreLog {

    @Volatile
    var logger: CoreLogger = NoopCoreLogger

    fun d(tag: String, message: String) = logger.d(tag, message)
    fun i(tag: String, message: String) = logger.i(tag, message)
    fun w(tag: String, message: String, error: Throwable? = null) = logger.w(tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = logger.e(tag, message, error)
}
