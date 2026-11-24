package io.github.hazymorning.amsel.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.hazymorning.amsel.api.MaJson
import io.github.hazymorning.amsel.api.ServerAddress
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.api.forUrl
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class FoundServer(val address: ServerAddress, val info: ServerInfo)

class ServerDiscovery(private val context: Context, private val http: OkHttpClient) {
    fun servers(): Flow<List<FoundServer>> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        val found = linkedMapOf<String, FoundServer>()
        // Resolved one at a time: before Android 14 a second resolve fails while one runs.
        val announced = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        var started = true
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                announced.trySend(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) = Unit

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                started = false
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        launch {
            for (service in announced) {
                val resolved = resolve(nsd, service) ?: continue
                launch {
                    val server = confirm(resolved) ?: return@launch
                    if (found.put(server.info.serverId, server) == null) trySend(found.values.toList())
                }
            }
        }
        trySend(emptyList())
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { if (started) nsd.stopServiceDiscovery(listener) }
    }

    // resolveService is deprecated from API 34, where registerServiceInfoCallback replaces it.
    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, service: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val registered = AtomicBoolean(true)
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceUpdated(info: NsdServiceInfo) {
                        // Updates keep coming while registered; the first one is the answer.
                        if (registered.compareAndSet(true, false)) {
                            nsd.unregisterServiceInfoCallback(this)
                            cont.resume(info)
                        }
                    }

                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        registered.set(false)
                        cont.resume(null)
                    }

                    override fun onServiceLost() = Unit

                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                nsd.registerServiceInfoCallback(service, context.mainExecutor, callback)
                cont.invokeOnCancellation {
                    if (registered.compareAndSet(true, false)) nsd.unregisterServiceInfoCallback(callback)
                }
            } else {
                nsd.resolveService(
                    service,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = cont.resume(null)

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) = cont.resume(serviceInfo)
                    },
                )
            }
        }

    // Resolved addresses, IPv4 first, then TXT `base_url`, which can be container-internal. Every server announces
    // `mass.local`, so one host can carry several servers' addresses: keep the first with the announced `server_id`.
    // See docs/API-NOTES.md, "Discovery".
    private suspend fun confirm(service: NsdServiceInfo): FoundServer? {
        // NsdServiceInfo.host is deprecated from API 34, where hostAddresses replaces it.
        @Suppress("DEPRECATION")
        val hosts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.hostAddresses
        } else {
            listOfNotNull(service.host)
        }
        val candidates = hosts.sortedBy { it !is Inet4Address }.mapNotNull { host ->
            val text = host.hostAddress ?: return@mapNotNull null
            ServerAddress.parse(if (':' in text) "[$text]:${service.port}" else "$text:${service.port}")
        } + listOfNotNull(service.attributes["base_url"]?.let { ServerAddress.fromBaseUrl(String(it)) })
        val expectedId = service.attributes["server_id"]?.let { String(it) }
        for (address in candidates.distinct()) {
            val info = info(address) ?: continue
            if (expectedId == null || info.serverId == expectedId) return FoundServer(address, info)
        }
        return null
    }

    private suspend fun info(address: ServerAddress): ServerInfo? {
        val url = address.baseUrl.newBuilder().addPathSegment("info").build()
        val call = http.forUrl(url).newCall(Request.Builder().url(url).build())
        val body = suspendCancellableCoroutine<String?> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) = cont.resume(null)

                    override fun onResponse(call: Call, response: Response) = response.use {
                        cont.resume(
                            try {
                                // Capped: anything on the network can announce itself and answer.
                                if (it.isSuccessful) it.peekBody(MAX_INFO_BYTES).string() else null
                            } catch (e: IOException) {
                                null
                            },
                        )
                    }
                },
            )
        } ?: return null
        return try {
            MaJson.decodeFromString(ServerInfo.serializer(), body)
        } catch (e: SerializationException) {
            null
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_mass._tcp"

        // Far more than any server_info; a longer body is not Music Assistant.
        const val MAX_INFO_BYTES = 64L * 1024
    }
}
