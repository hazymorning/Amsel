package io.github.hazymorning.amsel.api

import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import javax.net.SocketFactory
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * Plain http ([cleartext] for the connection about to open) reaches only local and VPN addresses:
 * Android allows cleartext to all hosts or none. Checked at socket connect, as WebSockets skip
 * network interceptors. Never proxied: the check would see the proxy's address, the proxy the token.
 */
public fun OkHttpClient.localCleartext(cleartext: () -> Boolean = { true }): OkHttpClient {
    val permitted = (socketFactory as? PlainSocketFactory)?.permitted ?: { true }
    return newBuilder()
        .proxySelector(DirectCleartextProxySelector(proxySelector))
        .socketFactory(LocalCleartextSocketFactory(cleartext, permitted))
        .build()
}

/**
 * Carries the app's rule for plain http: in clients made from this one with [localCleartext] or
 * [forUrl], no plain http connects while [permitted] is false, whatever the address.
 */
public fun OkHttpClient.plainHttpOnlyWhen(permitted: () -> Boolean): OkHttpClient =
    newBuilder().socketFactory(PlainSocketFactory(permitted)).build()

public fun OkHttpClient.forUrl(url: HttpUrl): OkHttpClient = if (url.isHttps) this else localCleartext()

public fun InetAddress.isLocal(): Boolean = isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress ||
    when (this) {
        // 100.64.0.0/10, where VPNs such as Tailscale put their peers.
        is Inet4Address -> address[0].toInt() == 100 && (address[1].toInt() and 0xC0) == 0x40

        // fc00::/7, unique local addresses.
        is Inet6Address -> (address[0].toInt() and 0xFE) == 0xFC

        else -> false
    }

/** Plain sockets without checks; it only carries [permitted] on to the clients derived from this one. */
private open class PlainSocketFactory(val permitted: () -> Boolean) : SocketFactory() {
    override fun createSocket(): Socket = Socket()

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}

private class LocalCleartextSocketFactory(private val cleartext: () -> Boolean, permitted: () -> Boolean) :
    PlainSocketFactory(permitted) {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            if (cleartext()) {
                val address = (endpoint as? InetSocketAddress)?.address
                if (address?.isLocal() != true) throw ConnectException("Plain http only reaches the local network")
                if (!permitted()) throw ConnectException("Plain http is not allowed on this network")
            }
            super.connect(endpoint, timeout)
        }
    }
}

/** Equal for equal [proxies]: OkHttp reuses a pooled connection only for an equal proxy selector. */
private data class DirectCleartextProxySelector(private val proxies: ProxySelector) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> =
        if (uri.scheme == "http") listOf(Proxy.NO_PROXY) else proxies.select(uri)

    override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) =
        proxies.connectFailed(uri, address, failure)
}
