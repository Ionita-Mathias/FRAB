package ch.genedis.tvfileserver.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.genedis.tvfileserver.appContainer
import ch.genedis.tvfileserver.server.ServerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Exposes the shared server state to the TV screen and renders the QR code off the main thread. */
class ServerViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer

    val state: StateFlow<ServerUiState> = container.serverManager.state

    /**
     * The QR bitmap for the current connection URL.
     *
     * Recomputed only when the payload actually changes, and always off the main thread:
     * encoding a 480 px code takes a few tens of milliseconds on this hardware.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val qrBitmap: StateFlow<Bitmap?> = state
        .map { it.qrPayload }
        .distinctUntilChanged()
        .mapLatest { payload ->
            if (payload.isNullOrEmpty()) {
                null
            } else {
                withContext(Dispatchers.Default) { QrCodeGenerator.encode(payload, QR_SIZE_PX) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun refresh() = container.serverManager.refreshEnvironment()

    fun regeneratePassword() = container.serverManager.regeneratePassword()

    fun applySettings() = container.serverManager.applySettings()

    private companion object {
        const val QR_SIZE_PX = 480
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
