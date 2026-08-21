package ch.genedis.tvfileserver.ui

import android.os.Bundle
import android.text.InputType
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import ch.genedis.tvfileserver.R
import ch.genedis.tvfileserver.appContainer
import ch.genedis.tvfileserver.settings.ServerPreferences
import kotlinx.coroutines.launch

/**
 * The settings screen, as a Leanback guided step.
 *
 * `GuidedStepSupportFragment` is the idiomatic TV settings surface: the framework handles
 * D-Pad navigation, the focus ring and the on-screen keyboard for the numeric fields, none
 * of which is worth reimplementing on a custom layout.
 */
class SettingsFragment : GuidedStepSupportFragment() {

    private var loaded: ServerPreferences = ServerPreferences()

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance =
        GuidanceStylist.Guidance(
            getString(R.string.settings_title),
            getString(R.string.settings_description),
            getString(R.string.app_name),
            null,
        )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        // The list is built from defaults first so the screen renders immediately, then
        // refreshed once DataStore has answered.
        buildActions(actions, loaded)
        lifecycleScope.launch {
            loaded = requireContext().appContainer.settings.ensureInitialised()
            val refreshed = mutableListOf<GuidedAction>()
            buildActions(refreshed, loaded)
            setActions(refreshed)
        }
    }

    private fun buildActions(actions: MutableList<GuidedAction>, preferences: ServerPreferences) {
        actions.add(
            numeric(ID_HTTP_PORT, R.string.settings_http_port, R.string.settings_http_port_desc, preferences.httpPort),
        )
        actions.add(
            numeric(ID_FTP_PORT, R.string.settings_ftp_port, R.string.settings_ftp_port_desc, preferences.ftpPort),
        )
        actions.add(toggle(ID_FTP_ENABLED, R.string.settings_ftp_enabled, R.string.settings_ftp_enabled_desc, preferences.ftpEnabled))
        actions.add(toggle(ID_WEBDAV_ENABLED, R.string.settings_webdav_enabled, R.string.settings_webdav_enabled_desc, preferences.webdavEnabled))
        actions.add(toggle(ID_AUTH_ENABLED, R.string.settings_auth_enabled, R.string.settings_auth_enabled_desc, preferences.authEnabled))
        actions.add(toggle(ID_ANONYMOUS_READ, R.string.settings_anonymous_read, R.string.settings_anonymous_read_desc, preferences.allowAnonymousRead))
        actions.add(toggle(ID_READ_ONLY, R.string.settings_read_only, R.string.settings_read_only_desc, preferences.readOnly))
        actions.add(toggle(ID_HIDE_DOT_FILES, R.string.settings_hide_dot_files, R.string.settings_hide_dot_files_desc, preferences.hideDotFiles))
        actions.add(toggle(ID_START_ON_BOOT, R.string.settings_start_on_boot, R.string.settings_start_on_boot_desc, preferences.startOnBoot))
        actions.add(toggle(ID_AUTO_START, R.string.settings_auto_start, R.string.settings_auto_start_desc, preferences.autoStartOnLaunch))
        actions.add(toggle(ID_EXPOSE_APP_DIRS, R.string.settings_expose_app_dirs, R.string.settings_expose_app_dirs_desc, preferences.exposeAppPrivateDirs))

        actions.add(
            GuidedAction.Builder(requireContext())
                .id(ID_APPLY)
                .title(R.string.settings_apply)
                .description(R.string.settings_apply_desc)
                .build(),
        )
    }

    private fun toggle(id: Long, titleRes: Int, descriptionRes: Int, checked: Boolean): GuidedAction =
        GuidedAction.Builder(requireContext())
            .id(id)
            .title(titleRes)
            .description(descriptionRes)
            .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
            .checked(checked)
            .build()

    private fun numeric(id: Long, titleRes: Int, descriptionRes: Int, value: Int): GuidedAction =
        GuidedAction.Builder(requireContext())
            .id(id)
            .title(titleRes)
            .description(descriptionRes)
            .editable(true)
            .editInputType(InputType.TYPE_CLASS_NUMBER)
            .editTitle(value.toString())
            .build()

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_APPLY -> {
                persist()
                finishGuidedStepSupportFragments()
            }
            else -> if (action.checkSetId == GuidedAction.CHECKBOX_CHECK_SET_ID) {
                loaded = applyToggle(action.id, action.isChecked)
            }
        }
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        val entered = action.editTitle?.toString()?.trim()?.toIntOrNull()
        if (entered != null && entered in 1..65535) {
            loaded = when (action.id) {
                ID_HTTP_PORT -> loaded.copy(httpPort = entered)
                ID_FTP_PORT -> loaded.copy(ftpPort = entered)
                else -> loaded
            }
            action.title = getString(if (action.id == ID_HTTP_PORT) R.string.settings_http_port else R.string.settings_ftp_port)
        } else {
            // Restore the last good value rather than persisting a port that cannot bind.
            val fallback = if (action.id == ID_HTTP_PORT) loaded.httpPort else loaded.ftpPort
            action.editTitle = fallback.toString()
        }
        return GuidedAction.ACTION_ID_CURRENT
    }

    private fun applyToggle(id: Long, checked: Boolean): ServerPreferences = when (id) {
        ID_FTP_ENABLED -> loaded.copy(ftpEnabled = checked)
        ID_WEBDAV_ENABLED -> loaded.copy(webdavEnabled = checked)
        ID_AUTH_ENABLED -> loaded.copy(authEnabled = checked)
        ID_ANONYMOUS_READ -> loaded.copy(allowAnonymousRead = checked)
        ID_READ_ONLY -> loaded.copy(readOnly = checked)
        ID_HIDE_DOT_FILES -> loaded.copy(hideDotFiles = checked)
        ID_START_ON_BOOT -> loaded.copy(startOnBoot = checked)
        ID_AUTO_START -> loaded.copy(autoStartOnLaunch = checked)
        ID_EXPOSE_APP_DIRS -> loaded.copy(exposeAppPrivateDirs = checked)
        else -> loaded
    }

    private fun persist() {
        val snapshot = loaded
        val container = requireContext().applicationContext.appContainer
        // Deliberately not lifecycleScope: this fragment is finishing, and the write must
        // still land and be applied to the running server.
        container.applicationScope.launch {
            container.settings.update { snapshot }
            container.serverManager.applySettings()
        }
    }

    private companion object {
        const val ID_HTTP_PORT = 1L
        const val ID_FTP_PORT = 2L
        const val ID_FTP_ENABLED = 3L
        const val ID_WEBDAV_ENABLED = 4L
        const val ID_AUTH_ENABLED = 5L
        const val ID_ANONYMOUS_READ = 6L
        const val ID_READ_ONLY = 7L
        const val ID_HIDE_DOT_FILES = 8L
        const val ID_START_ON_BOOT = 9L
        const val ID_AUTO_START = 10L
        const val ID_EXPOSE_APP_DIRS = 11L
        const val ID_APPLY = 100L
    }
}
