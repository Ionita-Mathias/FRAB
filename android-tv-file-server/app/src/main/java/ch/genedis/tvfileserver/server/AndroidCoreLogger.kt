package ch.genedis.tvfileserver.server

import android.util.Log
import ch.genedis.tvfileserver.BuildConfig
import ch.genedis.tvfileserver.core.util.CoreLogger

/**
 * Bridges the core module's logging onto `android.util.Log`.
 *
 * Debug logging is compiled out of release builds through the [BuildConfig.DEBUG] check, so
 * a busy transfer does not spend cycles formatting strings nobody reads.
 */
class AndroidCoreLogger(private val prefix: String = "TVFS/") : CoreLogger {

    override fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(prefix + tag, message)
    }

    override fun i(tag: String, message: String) {
        Log.i(prefix + tag, message)
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        if (error == null) Log.w(prefix + tag, message) else Log.w(prefix + tag, message, error)
    }

    override fun e(tag: String, message: String, error: Throwable?) {
        if (error == null) Log.e(prefix + tag, message) else Log.e(prefix + tag, message, error)
    }
}
