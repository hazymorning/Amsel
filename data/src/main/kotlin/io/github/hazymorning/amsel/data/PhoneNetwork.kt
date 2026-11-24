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
 * The default network's handle and, under a VPN, the handles of the networks under it, within one boot.
 * A handle belongs to one connection: the same Wi-Fi joined again is another key.
 */
data class NetworkKey(val boot: Int, val handles: List<Long>) {
    val text: String get() = "$boot:" + handles.joinToString(",")

    companion object {
        fun parse(text: String): NetworkKey? {
            val boot = text.substringBefore(':').toIntOrNull() ?: return null
            val handles = text.substringAfter(':').split(',').map { it.toLongOrNull() ?: return null }
            return NetworkKey(boot, handles)
        }
    }
}

interface PhoneNetwork {
    val current: StateFlow<NetworkKey?>

    /** False on mobile data without a VPN, where a private address routes into the carrier's network. */
    fun plainHttpAllowed(): Boolean
}

class AndroidPhoneNetwork(context: Context) : PhoneNetwork {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    // Network handles repeat after a reboot.
    private val boot = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Settings.SettingNotFoundException) {
        // Negative, so never a real boot count: keys stored in this run match no later one.
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
        // A NetworkRequest excludes VPNs by default (NOT_VPN), so this reports the networks under one.
        manager.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = update { beneath += network }

                override fun onLost(network: Network) = update { beneath -= network }
            },
        )
    }

    // Read at each plain http connection, not from the callbacks, which can lag behind.
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
