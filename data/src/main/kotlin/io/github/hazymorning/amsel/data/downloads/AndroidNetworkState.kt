package io.github.hazymorning.amsel.data.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Online: the default network has INTERNET, validated or not, as a home server answers where the internet check
// fails. Unmetered: some unmetered network is up, also under a VPN, which Android counts as metered itself.
class AndroidNetworkState(context: Context) : NetworkState {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(false)
    private val _unmetered = MutableStateFlow(false)
    private val free = ConcurrentHashMap.newKeySet<Network>()

    override val online: StateFlow<Boolean> = _online.asStateFlow()
    override val unmetered: StateFlow<Boolean> = _unmetered.asStateFlow()

    init {
        manager.activeNetwork?.let(manager::getNetworkCapabilities)?.let {
            _online.value = it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    _online.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }

                override fun onLost(network: Network) {
                    _online.value = false
                }
            },
        )
        // A NetworkRequest excludes VPNs by default (NOT_VPN), so this reports the networks under one.
        val unmetered = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            .build()
        manager.registerNetworkCallback(
            unmetered,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    free += network
                    _unmetered.value = true
                }

                override fun onLost(network: Network) {
                    free -= network
                    _unmetered.value = free.isNotEmpty()
                }
            },
        )
    }
}
