package ch.genedis.tvfileserver.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import ch.genedis.tvfileserver.R
import ch.genedis.tvfileserver.appContainer
import ch.genedis.tvfileserver.databinding.ActivityMainBinding
import ch.genedis.tvfileserver.server.FileServerService
import ch.genedis.tvfileserver.server.ServerStatus
import ch.genedis.tvfileserver.server.ServerUiState
import ch.genedis.tvfileserver.storage.AndroidStorage
import kotlinx.coroutines.launch

/**
 * The single TV screen: server status, connection details, QR code and live transfers.
 *
 * Every control is reachable with the D-Pad, and the focus order is declared explicitly in
 * the layout rather than left to the framework's geometric guess, which behaves badly when
 * buttons appear and disappear.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ServerViewModel by viewModels()

    private val transferAdapter = TransferAdapter()
    private val rootAdapter = RootAdapter()

    private var focusInitialised = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Log.i(TAG, "Notifications were refused; the server still runs without a status card")
            }
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.refresh()
        }

    private val allFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.listTransfers.layoutManager = LinearLayoutManager(this)
        binding.listTransfers.adapter = transferAdapter
        binding.listTransfers.setHasFixedSize(true)

        binding.listRoots.layoutManager = LinearLayoutManager(this)
        binding.listRoots.adapter = rootAdapter
        binding.listRoots.setHasFixedSize(true)

        wireButtons()
        requestNotificationPermissionIfNeeded()
        observeState()
        maybeAutoStart()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun wireButtons() {
        // The service owns the lifecycle so the server survives this Activity being finished.
        binding.btnToggle.setOnClickListener {
            if (viewModel.state.value.isRunning) {
                FileServerService.stop(this)
            } else {
                FileServerService.start(this)
            }
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnNewPassword.setOnClickListener { viewModel.regeneratePassword() }
        binding.btnRefresh.setOnClickListener { viewModel.refresh() }
        binding.btnPermissions.setOnClickListener { requestStorageAccess() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch {
                    viewModel.qrBitmap.collect { bitmap ->
                        binding.imgQr.setImageBitmap(bitmap)
                        binding.imgQr.visibility = if (bitmap == null) View.INVISIBLE else View.VISIBLE
                    }
                }
            }
        }
    }

    private fun maybeAutoStart() {
        lifecycleScope.launch {
            val preferences = appContainer.settings.ensureInitialised()
            if (preferences.autoStartOnLaunch && !viewModel.state.value.isRunning) {
                FileServerService.start(this@MainActivity)
            }
        }
    }

    private fun render(state: ServerUiState) {
        renderStatus(state)
        renderConnection(state)
        renderTransfers(state)
        renderRoots(state)
        renderPermissions(state)
        applyKeepScreenOn(state)

        if (!focusInitialised) {
            focusInitialised = true
            binding.btnToggle.requestFocus()
        }
    }

    private fun renderStatus(state: ServerUiState) {
        val (labelRes, colorRes) = when (state.status) {
            ServerStatus.RUNNING -> R.string.status_running to R.color.status_running
            ServerStatus.STARTING -> R.string.status_starting to R.color.status_pending
            ServerStatus.STOPPING -> R.string.status_stopping to R.color.status_pending
            ServerStatus.ERROR -> R.string.status_error to R.color.status_error
            ServerStatus.STOPPED -> R.string.status_stopped to R.color.status_stopped
        }
        binding.txtStatus.setText(labelRes)
        binding.txtStatus.backgroundTintList =
            ContextCompat.getColorStateList(this, colorRes)

        binding.btnToggle.setText(if (state.isRunning) R.string.action_stop else R.string.action_start)
        binding.btnToggle.isEnabled = !state.isBusy

        binding.txtDevice.text = getString(R.string.subtitle_device, state.deviceName)

        val error = state.errorMessage
        binding.txtError.visibility = if (error.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.txtError.text = error.orEmpty()
    }

    private fun renderConnection(state: ServerUiState) {
        val unavailable = getString(R.string.value_unavailable)
        binding.txtWebUrl.text = getString(R.string.label_web_url, state.webUrl ?: unavailable)
        binding.txtDavUrl.text = getString(R.string.label_dav_url, state.davUrl ?: unavailable)
        binding.txtFtpUrl.text = getString(R.string.label_ftp_url, state.ftpUrl ?: unavailable)
        binding.txtCredentials.text = if (state.authEnabled) {
            getString(R.string.label_credentials, state.username, state.password)
        } else {
            getString(R.string.label_no_auth)
        }

        binding.txtHint.text = when {
            !state.isRunning -> getString(R.string.hint_stopped)
            state.readOnly -> getString(R.string.hint_read_only)
            else -> getString(R.string.hint_running)
        }
    }

    private fun renderTransfers(state: ServerUiState) {
        // Only the first few rows fit next to the QR code, and a TV screen is not the place
        // to scroll a list nobody can focus.
        transferAdapter.submitList(state.transfers.take(MAX_VISIBLE_TRANSFERS))
        val hasTransfers = state.transfers.isNotEmpty()
        binding.listTransfers.visibility = if (hasTransfers) View.VISIBLE else View.GONE
        binding.txtTransfersEmpty.visibility = if (hasTransfers) View.GONE else View.VISIBLE
        binding.txtTransfersEmpty.text = if (state.isRunning) {
            getString(R.string.transfers_idle)
        } else {
            getString(R.string.transfers_offline)
        }
    }

    private fun renderRoots(state: ServerUiState) {
        // The space figures were already gathered on the IO dispatcher by ServerManager.
        rootAdapter.submitList(state.roots)
    }

    private fun renderPermissions(state: ServerUiState) {
        val needsAccess = !state.hasStoragePermission
        binding.btnPermissions.visibility = if (needsAccess) View.VISIBLE else View.GONE
        // An explicit nextFocus pointing at a GONE view simply stops the D-Pad, so both ends
        // of the chain are re-linked whenever the button appears or disappears.
        binding.btnNewPassword.nextFocusDownId =
            if (needsAccess) R.id.btn_permissions else R.id.btn_refresh
        binding.btnRefresh.nextFocusUpId =
            if (needsAccess) R.id.btn_permissions else R.id.btn_new_password
    }

    private fun applyKeepScreenOn(state: ServerUiState) {
        val keepOn = state.isRunning && state.transfers.isNotEmpty()
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Asks for whatever storage access this API level uses.
     *
     * On Android 11+ that means the all-files screen, which a surprising number of TV builds
     * simply do not ship; the fallback explains where to look instead of crashing.
     */
    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = AndroidStorage.manageAllFilesIntent(this)
            if (intent == null) {
                showManualPermissionDialog()
                return
            }
            try {
                allFilesAccessLauncher.launch(intent)
            } catch (error: ActivityNotFoundException) {
                Log.w(TAG, "No activity handles the all-files-access intent", error)
                showManualPermissionDialog()
            }
            return
        }
        storagePermissionLauncher.launch(AndroidStorage.requiredRuntimePermissions())
    }

    private fun showManualPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_dialog_title)
            .setMessage(R.string.permission_dialog_message)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val MAX_VISIBLE_TRANSFERS = 4
    }
}
