package ch.genedis.tvfileserver.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/**
 * Publishes the server over mDNS/Bonjour.
 *
 * This is what makes the share turn up by itself in the macOS Finder sidebar and in network
 * scanners on iOS and Android, instead of the user having to type an IP address read off
 * the television.
 */
class NsdRegistrar(context: Context) {

    private val appContext = context.applicationContext
    private val manager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val listeners = mutableListOf<NsdManager.RegistrationListener>()

    /** Registers one Bonjour record per enabled protocol. Safe to call twice. */
    fun register(
        deviceName: String,
        httpPort: Int,
        ftpPort: Int,
        ftpEnabled: Boolean,
        webdavEnabled: Boolean,
        webdavPath: String,
    ) {
        val nsd = manager ?: run {
            Log.w(TAG, "This device has no NSD service; discovery is unavailable")
            return
        }
        unregister()

        val baseName = sanitize(deviceName)
        if (httpPort > 0) {
            registerOne(nsd, buildInfo("$baseName File Server", "_http._tcp", httpPort, mapOf("path" to "/")))
            if (webdavEnabled) {
                registerOne(
                    nsd,
                    buildInfo("$baseName WebDAV", "_webdav._tcp", httpPort, mapOf("path" to webdavPath)),
                )
            }
        }
        if (ftpEnabled && ftpPort > 0) {
            registerOne(nsd, buildInfo("$baseName FTP", "_ftp._tcp", ftpPort, emptyMap()))
        }
    }

    /** Withdraws every record. Never throws, so it is safe on a teardown path. */
    fun unregister() {
        val nsd = manager ?: return
        for (listener in listeners.toList()) {
            try {
                nsd.unregisterService(listener)
            } catch (error: IllegalArgumentException) {
                // Thrown when the listener was never successfully registered.
                Log.d(TAG, "Listener was not registered: ${error.message}")
            }
        }
        listeners.clear()
    }

    private fun registerOne(nsd: NsdManager, info: NsdServiceInfo) {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Registered ${info.serviceName} (${info.serviceType})")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Cannot register ${info.serviceType}: error $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d(TAG, "Unregistered ${info.serviceType}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Cannot unregister ${info.serviceType}: error $errorCode")
            }
        }
        listeners.add(listener)
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Rejected service registration for ${info.serviceType}", error)
            listeners.remove(listener)
        }
    }

    private fun buildInfo(
        name: String,
        type: String,
        port: Int,
        attributes: Map<String, String>,
    ): NsdServiceInfo = NsdServiceInfo().apply {
        serviceName = truncateToBonjourLimit(name)
        serviceType = type
        setPort(port)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for ((key, value) in attributes) setAttribute(key, value)
        }
    }

    /** Bonjour instance names are limited to 63 bytes of UTF-8. */
    private fun truncateToBonjourLimit(name: String): String {
        var candidate = name
        while (candidate.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES && candidate.isNotEmpty()) {
            candidate = candidate.substring(0, candidate.length - 1)
        }
        return candidate.ifEmpty { "TV File Server" }
    }

    private fun sanitize(deviceName: String): String =
        deviceName.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.trim().ifEmpty { "Android TV" }

    private companion object {
        const val TAG = "NsdRegistrar"
        const val MAX_NAME_BYTES = 63
    }
}
