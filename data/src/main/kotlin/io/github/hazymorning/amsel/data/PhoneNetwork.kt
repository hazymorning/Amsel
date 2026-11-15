package io.github.hazymorning.amsel.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The networks the phone's traffic takes, in one boot of the phone: the default network and, under
 * a VPN, the networks beneath it. Each connection to a network has a handle of its own, so the same
 * Wi-Fi joined again is another key.
 */
data class NetworkKey(val boot: Int, val handles: List<Long>) {
    /** As kept in the preferences. */
    val text: String get() = "$boot:" + handles.joinToString(",")

    companion object {
        fun parse(text: String): NetworkKey? {
            val boot = text.substringBefore(':').toIntOrNull() ?: return null
            val handles = text.substringAfter(':').split(',').map { it.toLongOrNull() ?: return null }
            return NetworkKey(boot, handles)
        }
    }
}

/** Which network the phone is on, and whether plain http may leave the phone there. */
interface PhoneNetwork {
    /** Null while the phone has no network. */
    val current: StateFlow<NetworkKey?>

    /** False on mobile data without a VPN, where a private address leads into the carrier's network. */
    fun plainHttpAllowed(): Boolean
}

class AndroidPhoneNetwork(context: Context) : PhoneNetwork {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    // Android hands out handles from the same numbers again after the phone restarts.
    private val boot = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Settings.SettingNotFoundException) {
        // A key kept by this run then matches no later one.
        Random.nextInt(Int.MIN_VALUE, 0)
    }
    private var default: Network? = null
    private var underVpn = false
    private val beneath = mutableSetOf<Network>()
    private val _current = MutableStateFlow<NetworkKey?>(null)

    override val current: StateFlow<NetworkKey?> = _current.asStateFlow()

    init {
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update {
                    default = network
                    underVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }

                override fun onLost(network: Network) = update { if (default == network) default = null }
            },
        )
        // A request leaves VPNs out by itself, so this hears the networks beneath one.
        manager.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = update { beneath += network }

                override fun onLost(network: Network) = update { beneath -= network }
            },
        )
    }

    // Asked as each plain http connection opens, not from the callbacks, which may come a moment late.
    override fun plainHttpAllowed(): Boolean {
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities) ?: return true
        return !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    @Synchronized
    private fun update(change: () -> Unit) {
        change()
        _current.value = default?.let { network ->
            val under = if (underVpn) beneath.map { it.networkHandle }.sorted() else emptyList()
            NetworkKey(boot, listOf(network.networkHandle) + under)
        }
    }
}
