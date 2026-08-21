package ch.genedis.tvfileserver.server

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ch.genedis.tvfileserver.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the server alive while the launcher UI is gone.
 *
 * The service does not own the server: [ServerManager] does, and it lives in the
 * application object. The service exists to hold the foreground notification and the
 * wake/Wi-Fi locks, and to shut itself down once the server stops.
 */
class FileServerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var nsd: NsdRegistrar? = null
    private var startedForeground = false

    private val serverManager get() = appContainer.serverManager

    override fun onCreate() {
        super.onCreate()
        ServerNotifications.ensureChannel(this)
        nsd = NsdRegistrar(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 8+ kills a service that does not call startForeground almost immediately,
        // so the notification is posted before anything slower happens.
        promoteToForeground()

        when (intent?.action) {
            ACTION_STOP -> {
                serverManager.stop()
                releaseLocks()
            }
            ACTION_TOGGLE -> serverManager.toggle()
            else -> {
                acquireLocks()
                serverManager.start()
            }
        }

        if (!observing) {
            observing = true
            observeState()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        nsd?.unregister()
        nsd = null
        serviceScope.cancel()
        startedForeground = false
        observing = false
        super.onDestroy()
    }

    /**
     * Dismissing the app from the recents list must not stop a running transfer: staying up
     * with the launcher closed is the entire point of this service.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed; the server keeps running")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ internals

    private fun promoteToForeground() {
        if (startedForeground) return
        val notification = ServerNotifications.build(this, serverManager.state.value)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, ServerNotifications.NOTIFICATION_ID, notification, type)
        startedForeground = true
    }

    // sample() is still preview API in coroutines 1.8; the alternative is hand-rolling the
    // same throttle, which is not worth the extra state.
    @OptIn(FlowPreview::class)
    private fun observeState() {
        serviceScope.launch {
            serverManager.state
                // The notification only shows a handful of fields, so redraw at most once a
                // second and only when one of those fields actually moved.
                .sample(NOTIFICATION_INTERVAL_MS)
                .map { NotificationKey(it.status, it.webUrl, it.totals.activeCount, it.password) to it }
                .distinctUntilChanged { previous, next -> previous.first == next.first }
                .collect { (_, state) ->
                    when (state.status) {
                        ServerStatus.RUNNING -> {
                            publishNotification(state)
                            registerDiscovery(state)
                        }
                        ServerStatus.STOPPED, ServerStatus.ERROR -> {
                            nsd?.unregister()
                            releaseLocks()
                            stopSelfCleanly()
                        }
                        else -> publishNotification(state)
                    }
                }
        }
    }

    private fun publishNotification(state: ServerUiState) {
        if (!startedForeground) return
        val manager = NotificationManagerCompat.from(this)
        try {
            manager.notify(ServerNotifications.NOTIFICATION_ID, ServerNotifications.build(this, state))
        } catch (error: SecurityException) {
            // POST_NOTIFICATIONS was refused on Android 13+. The service still runs; the
            // user just does not see the status card.
            Log.w(TAG, "Cannot post the status notification", error)
        }
    }

    private fun registerDiscovery(state: ServerUiState) {
        if (discoveryKey == state.httpPort to state.ftpPort) return
        discoveryKey = state.httpPort to state.ftpPort
        nsd?.register(
            deviceName = state.deviceName,
            httpPort = state.httpPort,
            ftpPort = state.ftpPort,
            ftpEnabled = state.ftpEnabled,
            webdavEnabled = state.webdavEnabled,
            webdavPath = state.webdavMount,
        )
    }

    private fun stopSelfCleanly() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        startedForeground = false
        stopSelf()
    }

    /**
     * Holds the locks a background server needs.
     *
     * Without the Wi-Fi lock, Android powers the radio down when the screen sleeps and the
     * server silently becomes unreachable; the multicast lock is what keeps Bonjour
     * answering; the partial wake lock stops a long upload being suspended mid-file.
     */
    private fun acquireLocks() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager != null) {
            if (wifiLock == null) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)
                    .apply { setReferenceCounted(false) }
            }
            if (multicastLock == null) {
                multicastLock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG)
                    .apply { setReferenceCounted(false) }
            }
            wifiLock?.takeIf { !it.isHeld }?.acquire()
            multicastLock?.takeIf { !it.isHeld }?.acquire()
        }

        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null && wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
        }
        wakeLock?.takeIf { !it.isHeld }?.acquire()
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        multicastLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
    }

    private var observing = false
    private var discoveryKey: Pair<Int, Int>? = null

    private data class NotificationKey(
        val status: ServerStatus,
        val url: String?,
        val activeCount: Int,
        val password: String,
    )

    companion object {
        private const val TAG = "FileServerService"
        private const val NOTIFICATION_INTERVAL_MS = 1000L
        private const val WIFI_LOCK_TAG = "TvFileServer:wifi"
        private const val MULTICAST_LOCK_TAG = "TvFileServer:mdns"
        private const val WAKE_LOCK_TAG = "TvFileServer::server"

        const val ACTION_START = "ch.genedis.tvfileserver.action.START"
        const val ACTION_STOP = "ch.genedis.tvfileserver.action.STOP"
        const val ACTION_TOGGLE = "ch.genedis.tvfileserver.action.TOGGLE"

        fun intent(context: Context, action: String): Intent =
            Intent(context, FileServerService::class.java).setAction(action)

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_START))
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_STOP))
        }
    }
}
