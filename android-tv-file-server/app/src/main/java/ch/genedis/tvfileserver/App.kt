package ch.genedis.tvfileserver

import android.app.Application
import android.content.Context
import ch.genedis.tvfileserver.core.util.CoreLog
import ch.genedis.tvfileserver.server.AndroidCoreLogger
import ch.genedis.tvfileserver.server.ServerManager
import ch.genedis.tvfileserver.server.ServerNotifications
import ch.genedis.tvfileserver.settings.ServerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The process-wide object graph.
 *
 * Hand-rolled rather than injected: there are two dependencies, and a DI framework would
 * cost start-up time on hardware where every millisecond before the first frame shows.
 */
class AppContainer(context: Context) {
    val settings: ServerSettings = ServerSettings(context)
    val serverManager: ServerManager = ServerManager(context, settings)

    /**
     * A scope that outlives every Activity and Fragment.
     *
     * Settings writes must complete even when the screen that triggered them is finishing,
     * which rules out `lifecycleScope` for those.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CoreLog.logger = AndroidCoreLogger()
        container = AppContainer(this)
        ServerNotifications.ensureChannel(this)
    }
}

/**
 * Reaches the container from any [Context].
 *
 * @throws IllegalStateException when the application class was replaced, which would mean
 *   the manifest and this file disagree.
 */
val Context.appContainer: AppContainer
    get() {
        val application = applicationContext as? App
            ?: error("Application is not ch.genedis.tvfileserver.App; check the manifest")
        return application.container
    }
