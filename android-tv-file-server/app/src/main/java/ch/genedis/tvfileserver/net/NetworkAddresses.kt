package ch.genedis.tvfileserver.net

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Locale

/** One reachable address of this device. */
data class NetworkAddress(
    val interfaceName: String,
    val address: String,
    val isWifi: Boolean,
    val isEthernet: Boolean,
) {
    /** A label for the TV screen, e.g. `Ethernet` or `Wi-Fi`. */
    val label: String
        get() = when {
            isEthernet -> "Ethernet"
            isWifi -> "Wi-Fi"
            else -> interfaceName
        }
}

/**
 * Enumerates the LAN addresses the server can be reached on.
 *
 * `WifiManager` is deliberately not used: TV boxes are usually on Ethernet, and its IP is
 * invisible to the Wi-Fi APIs.
 */
object NetworkAddresses {

    private const val TAG = "NetworkAddresses"

    /**
     * All usable IPv4 addresses, private ranges first.
     *
     * Loopback, down interfaces and 169.254.x.x link-local addresses are dropped: none of
     * them is an address a phone on the sofa could reach.
     */
    fun localAddresses(): List<NetworkAddress> {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (error: SocketException) {
            Log.w(TAG, "Cannot enumerate network interfaces", error)
            return emptyList()
        }

        val result = ArrayList<NetworkAddress>(4)
        for (networkInterface in interfaces) {
            val up = try {
                networkInterface.isUp && !networkInterface.isLoopback
            } catch (error: SocketException) {
                false
            }
            if (!up) continue

            val name = networkInterface.name.orEmpty().lowercase(Locale.ROOT)
            val isWifi = name.startsWith("wlan") || name.startsWith("ap")
            val isEthernet = name.startsWith("eth") || name.startsWith("en")

            for (address in networkInterface.inetAddresses) {
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                val text = address.hostAddress ?: continue
                result.add(NetworkAddress(networkInterface.name, text, isWifi, isEthernet))
            }
        }
        // Site-local (192.168/10./172.16) first: that is what the user actually types.
        return result.sortedWith(
            compareByDescending<NetworkAddress> { isSiteLocal(it.address) }
                .thenByDescending { it.isEthernet }
                .thenByDescending { it.isWifi }
                .thenBy { it.address },
        )
    }

    /** The address most likely to work, or null when the device is offline. */
    fun bestAddress(): String? = localAddresses().firstOrNull()?.address

    /**
     * A human-readable device name, sanitised so it is a legal Bonjour service name.
     *
     * `Settings.Global.DEVICE_NAME` is what the user set in the TV settings; `Build.MODEL`
     * is the fallback on builds that never populate it.
     */
    fun hostName(context: Context): String {
        val configured = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            } else {
                null
            }
        } catch (error: SecurityException) {
            Log.d(TAG, "Cannot read the device name: ${error.message}")
            null
        }

        val raw = configured?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android TV"
        val cleaned = raw.map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else '-' }
            .joinToString("")
            .trim()
            .take(40)
        return cleaned.ifEmpty { "Android TV" }
    }

    private fun isSiteLocal(address: String): Boolean =
        address.startsWith("192.168.") ||
            address.startsWith("10.") ||
            (address.startsWith("172.") && address.substringAfter("172.").substringBefore('.').toIntOrNull() in 16..31)
}
