package ch.genedis.tvfileserver.server

import android.content.Context
import android.util.Log
import ch.genedis.tvfileserver.BuildConfig
import ch.genedis.tvfileserver.core.FileServerCore
import ch.genedis.tvfileserver.core.config.CoreConfig
import ch.genedis.tvfileserver.core.vfs.LocalFileSystem
import ch.genedis.tvfileserver.core.vfs.VPath
import ch.genedis.tvfileserver.core.vfs.VfsRoot
import ch.genedis.tvfileserver.core.web.ServerInfo
import ch.genedis.tvfileserver.net.NetworkAddresses
import ch.genedis.tvfileserver.settings.ServerPreferences
import ch.genedis.tvfileserver.settings.ServerSettings
import ch.genedis.tvfileserver.storage.AndroidStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Owns the server for the whole process lifetime.
 *
 * The Activity and the foreground service both observe [state]; neither of them owns the
 * server, so rotating the screen or dismissing the launcher never disturbs a transfer.
 */
class ServerManager(context: Context, private val settings: ServerSettings) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Mutex()

    private val _state = MutableStateFlow(ServerUiState())
    val state: StateFlow<ServerUiState> = _state.asStateFlow()

    @Volatile
    private var core: FileServerCore? = null

    @Volatile
    private var currentPreferences: ServerPreferences = ServerPreferences()

    @Volatile
    private var discoveredRoots: List<VfsRoot> = emptyList()

    private var collectors: MutableList<Job> = mutableListOf()

    init {
        scope.launch { refreshEnvironmentInternal() }
    }

    /** Starts the server. Returns immediately; progress lands in [state]. */
    fun start() {
        scope.launch {
            lifecycleLock.withLock {
                if (_state.value.status == ServerStatus.RUNNING) return@withLock
                startInternal()
            }
        }
    }

    /** Stops the server. Returns immediately. */
    fun stop() {
        scope.launch {
            lifecycleLock.withLock { stopInternal() }
        }
    }

    fun toggle() {
        if (_state.value.isRunning) stop() else start()
    }

    /** Re-scans network interfaces, storage roots and permissions without touching the server. */
    fun refreshEnvironment() {
        scope.launch { refreshEnvironmentInternal() }
    }

    /** Replaces the password and applies it without dropping live transfers. */
    fun regeneratePassword() {
        scope.launch {
            val password = settings.regeneratePassword()
            currentPreferences = currentPreferences.copy(password = password)
            core?.updateConfig(configFrom(currentPreferences))
            publish()
        }
    }

    /** Re-reads the settings, restarting only if a listener has to be rebound. */
    fun applySettings() {
        scope.launch {
            lifecycleLock.withLock {
                val updated = settings.ensureInitialised()
                val previous = currentPreferences
                currentPreferences = updated

                val activeCore = core
                if (activeCore == null || _state.value.status != ServerStatus.RUNNING) {
                    publish()
                    return@withLock
                }
                val newConfig = configFrom(updated)
                if (activeCore.requiresRestart(configFrom(previous), newConfig)) {
                    Log.i(TAG, "Settings changed in a way that needs a rebind, restarting")
                    stopInternal()
                    startInternal()
                } else {
                    activeCore.updateConfig(newConfig)
                    publish()
                }
            }
        }
    }

    /** Releases every resource. Only called when the process is going away for good. */
    fun shutdown() {
        core?.stop()
        core = null
        scope.coroutineContext[Job]?.cancel()
    }

    // ------------------------------------------------------------------ internals

    private suspend fun startInternal() {
        _state.value = _state.value.copy(status = ServerStatus.STARTING, errorMessage = null)

        val preferences = settings.ensureInitialised()
        currentPreferences = preferences
        discoveredRoots = AndroidStorage.discoverRoots(appContext, preferences.exposeAppPrivateDirs)

        val config = configFrom(preferences)
        val server = core ?: FileServerCore(
            vfsProvider = {
                LocalFileSystem(
                    roots = discoveredRoots,
                    readOnly = currentPreferences.readOnly,
                    hideDotFiles = currentPreferences.hideDotFiles,
                )
            },
            assets = AndroidAssetSource(appContext.assets),
            infoProvider = { buildServerInfo() },
            initialConfig = config,
        ).also { core = it }

        server.updateConfig(config)

        try {
            val result = server.start(scope)
            cancelCollectors()
            observe(server)
            _state.value = _state.value.copy(
                status = ServerStatus.RUNNING,
                httpPort = result.httpPort,
                ftpPort = result.ftpPort,
                errorMessage = if (preferences.ftpEnabled && result.ftpPort <= 0) {
                    appContext.getString(
                        ch.genedis.tvfileserver.R.string.error_ftp_port_busy,
                        preferences.ftpPort,
                    )
                } else {
                    null
                },
            )
            publish()
            Log.i(TAG, "Server running on http=${result.httpPort} ftp=${result.ftpPort}")
        } catch (error: IOException) {
            Log.e(TAG, "Cannot start the server", error)
            server.stop()
            _state.value = _state.value.copy(
                status = ServerStatus.ERROR,
                errorMessage = appContext.getString(
                    ch.genedis.tvfileserver.R.string.error_http_port_busy,
                    preferences.httpPort,
                ),
            )
        }
    }

    private fun stopInternal() {
        if (_state.value.status == ServerStatus.STOPPED) return
        _state.value = _state.value.copy(status = ServerStatus.STOPPING)
        cancelCollectors()
        core?.stop()
        _state.value = _state.value.copy(
            status = ServerStatus.STOPPED,
            transfers = emptyList(),
            errorMessage = null,
        )
    }

    private fun observe(server: FileServerCore) {
        // A StateFlow is already conflated, and the registry additionally coalesces to four
        // updates a second per transfer, so a fast LAN copy cannot flood the TV's render loop.
        collectors.add(
            scope.launch {
                server.transfers.active.collect { active ->
                    _state.value = _state.value.copy(transfers = active)
                }
            },
        )
        collectors.add(
            scope.launch {
                server.transfers.totals.collect { totals ->
                    _state.value = _state.value.copy(totals = totals)
                }
            },
        )
    }

    private fun cancelCollectors() {
        for (job in collectors) job.cancel()
        collectors = mutableListOf()
    }

    private suspend fun refreshEnvironmentInternal() {
        val preferences = settings.ensureInitialised()
        currentPreferences = preferences
        discoveredRoots = AndroidStorage.discoverRoots(appContext, preferences.exposeAppPrivateDirs)
        publish()
    }

    /**
     * Rebuilds the parts of the state that come from the environment rather than the core.
     *
     * Always called from the manager's IO scope, which is what lets it stat the storage
     * roots for their free space without touching the main thread.
     */
    private fun publish() {
        val addresses = NetworkAddresses.localAddresses()
        val preferences = currentPreferences
        val activeCore = core
        val storage = summariseStorage()
        _state.value = _state.value.copy(
            httpPort = if (_state.value.status == ServerStatus.RUNNING) _state.value.httpPort else preferences.httpPort,
            ftpPort = if (_state.value.status == ServerStatus.RUNNING) _state.value.ftpPort else preferences.ftpPort,
            ftpEnabled = preferences.ftpEnabled,
            webdavEnabled = preferences.webdavEnabled,
            webdavMount = CoreConfig.DEFAULT_WEBDAV_MOUNT,
            addresses = addresses,
            primaryAddress = addresses.firstOrNull()?.address,
            username = preferences.username,
            password = preferences.password,
            authEnabled = preferences.authEnabled,
            readOnly = preferences.readOnly,
            roots = storage,
            hasStoragePermission = AndroidStorage.hasStorageAccess(appContext),
            autoLoginToken = activeCore?.authenticator?.sessions?.autoLoginToken,
            deviceName = NetworkAddresses.hostName(appContext),
        )
    }

    /** Reads free/total space for every discovered root through the same view the server serves. */
    private fun summariseStorage(): List<StorageSummary> {
        val roots = discoveredRoots
        if (roots.isEmpty()) return emptyList()
        val filesystem = LocalFileSystem(roots)
        return roots.map { root ->
            val path = VPath.ROOT.child(root.id)
            StorageSummary(root, filesystem.freeSpace(path), filesystem.totalSpace(path))
        }
    }

    private fun configFrom(preferences: ServerPreferences): CoreConfig = CoreConfig(
        httpPort = preferences.httpPort,
        ftpPort = preferences.ftpPort,
        httpEnabled = true,
        ftpEnabled = preferences.ftpEnabled,
        webdavEnabled = preferences.webdavEnabled,
        authEnabled = preferences.authEnabled,
        username = preferences.username,
        password = preferences.password,
        allowAnonymousRead = preferences.allowAnonymousRead,
        readOnly = preferences.readOnly,
        hideDotFiles = preferences.hideDotFiles,
        serverName = appContext.getString(ch.genedis.tvfileserver.R.string.app_name),
        appVersion = BuildConfig.VERSION_NAME,
    ).validated()

    private fun buildServerInfo(): ServerInfo {
        val snapshot = _state.value
        return ServerInfo(
            serverName = appContext.getString(ch.genedis.tvfileserver.R.string.app_name),
            appVersion = BuildConfig.VERSION_NAME,
            httpPort = snapshot.httpPort,
            ftpPort = snapshot.ftpPort,
            ftpEnabled = snapshot.ftpEnabled,
            webdavEnabled = snapshot.webdavEnabled,
            webdavMount = snapshot.webdavMount,
            addresses = snapshot.addresses.map { it.address },
            readOnly = snapshot.readOnly,
            authEnabled = snapshot.authEnabled,
            deviceName = snapshot.deviceName,
        )
    }

    private companion object {
        const val TAG = "ServerManager"
    }
}
