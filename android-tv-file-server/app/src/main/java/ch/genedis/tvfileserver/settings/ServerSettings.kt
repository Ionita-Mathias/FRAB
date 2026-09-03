package ch.genedis.tvfileserver.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.security.SecureRandom

/** The persisted server configuration, as a plain immutable value. */
data class ServerPreferences(
    val httpPort: Int = 8080,
    val ftpPort: Int = 2121,
    val ftpEnabled: Boolean = true,
    val webdavEnabled: Boolean = true,
    val authEnabled: Boolean = true,
    val username: String = "tv",
    val password: String = "",
    val allowAnonymousRead: Boolean = false,
    val readOnly: Boolean = false,
    val hideDotFiles: Boolean = true,
    val startOnBoot: Boolean = false,
    val autoStartOnLaunch: Boolean = true,
    val keepScreenOn: Boolean = false,
    val exposeAppPrivateDirs: Boolean = false,
)

private val Context.serverDataStore: DataStore<Preferences> by preferencesDataStore(name = "server_settings")

/**
 * Reads and writes the server configuration.
 *
 * A password is generated on first use and persisted, so the TV can display a stable
 * credential the user can type into Finder or a phone.
 */
class ServerSettings(context: Context) {

    private val appContext = context.applicationContext
    private val store get() = appContext.serverDataStore

    /**
     * The stored configuration.
     *
     * The flow is a pure projection: it never writes, so collecting it from the UI cannot
     * trigger disk I/O loops. Password generation happens in [ensureInitialised].
     */
    val preferences: Flow<ServerPreferences> = store.data
        .catch { error ->
            if (error is IOException) {
                Log.w(TAG, "Cannot read the settings store, falling back to defaults", error)
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { it.toServerPreferences() }

    suspend fun current(): ServerPreferences = preferences.first()

    /** Creates the password on first run so the UI always has one to show. */
    suspend fun ensureInitialised(): ServerPreferences {
        val existing = current()
        if (existing.password.isNotEmpty()) return existing
        val generated = generatePassword()
        store.edit { it[Keys.PASSWORD] = generated }
        return existing.copy(password = generated)
    }

    suspend fun update(transform: (ServerPreferences) -> ServerPreferences) {
        store.edit { mutable ->
            val updated = transform(mutable.toPreferencesSnapshot())
            mutable[Keys.HTTP_PORT] = updated.httpPort
            mutable[Keys.FTP_PORT] = updated.ftpPort
            mutable[Keys.FTP_ENABLED] = updated.ftpEnabled
            mutable[Keys.WEBDAV_ENABLED] = updated.webdavEnabled
            mutable[Keys.AUTH_ENABLED] = updated.authEnabled
            mutable[Keys.USERNAME] = updated.username
            mutable[Keys.PASSWORD] = updated.password
            mutable[Keys.ANONYMOUS_READ] = updated.allowAnonymousRead
            mutable[Keys.READ_ONLY] = updated.readOnly
            mutable[Keys.HIDE_DOT_FILES] = updated.hideDotFiles
            mutable[Keys.START_ON_BOOT] = updated.startOnBoot
            mutable[Keys.AUTO_START] = updated.autoStartOnLaunch
            mutable[Keys.KEEP_SCREEN_ON] = updated.keepScreenOn
            mutable[Keys.EXPOSE_APP_DIRS] = updated.exposeAppPrivateDirs
        }
    }

    /** Replaces the password with a freshly generated one and returns it. */
    suspend fun regeneratePassword(): String {
        val generated = generatePassword()
        store.edit { it[Keys.PASSWORD] = generated }
        return generated
    }

    private fun Preferences.toServerPreferences(): ServerPreferences {
        val defaults = ServerPreferences()
        return ServerPreferences(
            httpPort = this[Keys.HTTP_PORT] ?: defaults.httpPort,
            ftpPort = this[Keys.FTP_PORT] ?: defaults.ftpPort,
            ftpEnabled = this[Keys.FTP_ENABLED] ?: defaults.ftpEnabled,
            webdavEnabled = this[Keys.WEBDAV_ENABLED] ?: defaults.webdavEnabled,
            authEnabled = this[Keys.AUTH_ENABLED] ?: defaults.authEnabled,
            username = this[Keys.USERNAME] ?: defaults.username,
            password = this[Keys.PASSWORD] ?: defaults.password,
            allowAnonymousRead = this[Keys.ANONYMOUS_READ] ?: defaults.allowAnonymousRead,
            readOnly = this[Keys.READ_ONLY] ?: defaults.readOnly,
            hideDotFiles = this[Keys.HIDE_DOT_FILES] ?: defaults.hideDotFiles,
            startOnBoot = this[Keys.START_ON_BOOT] ?: defaults.startOnBoot,
            autoStartOnLaunch = this[Keys.AUTO_START] ?: defaults.autoStartOnLaunch,
            keepScreenOn = this[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            exposeAppPrivateDirs = this[Keys.EXPOSE_APP_DIRS] ?: defaults.exposeAppPrivateDirs,
        )
    }

    private fun Preferences.toPreferencesSnapshot(): ServerPreferences = toServerPreferences()

    private object Keys {
        val HTTP_PORT = intPreferencesKey("http_port")
        val FTP_PORT = intPreferencesKey("ftp_port")
        val FTP_ENABLED = booleanPreferencesKey("ftp_enabled")
        val WEBDAV_ENABLED = booleanPreferencesKey("webdav_enabled")
        val AUTH_ENABLED = booleanPreferencesKey("auth_enabled")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val ANONYMOUS_READ = booleanPreferencesKey("anonymous_read")
        val READ_ONLY = booleanPreferencesKey("read_only")
        val HIDE_DOT_FILES = booleanPreferencesKey("hide_dot_files")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val AUTO_START = booleanPreferencesKey("auto_start")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val EXPOSE_APP_DIRS = booleanPreferencesKey("expose_app_dirs")
    }

    companion object {
        private const val TAG = "ServerSettings"

        /**
         * Alphabet without look-alike characters.
         *
         * The password gets read off a TV across a room and typed on a phone keyboard, so
         * `0/O`, `1/l/I` and upper case are all left out. Thirty-one symbols over eight
         * characters is about 40 bits, which is plenty against a throttled LAN attacker.
         */
        private const val ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz"

        fun generatePassword(length: Int = 8): String {
            val random = SecureRandom()
            val builder = StringBuilder(length)
            repeat(length) { builder.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
            return builder.toString()
        }
    }
}
