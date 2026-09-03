package ch.genedis.tvfileserver.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ch.genedis.tvfileserver.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restarts the server after a reboot or an app update, when the user asked for it.
 *
 * Starting a foreground service from `BOOT_COMPLETED` is explicitly allowed, which is why
 * the service posts its notification immediately in `onStartCommand`.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in HANDLED_ACTIONS) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val preferences = appContext.appContainer.settings.current()
                if (preferences.startOnBoot) {
                    Log.i(TAG, "Starting the file server after $action")
                    FileServerService.start(appContext)
                } else {
                    Log.d(TAG, "startOnBoot is off; ignoring $action")
                }
            } catch (error: Exception) {
                Log.w(TAG, "Cannot handle $action", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"

        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // Some manufacturer builds only send this one after a fast boot.
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
