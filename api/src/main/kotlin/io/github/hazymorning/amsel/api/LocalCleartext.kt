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
 * Plain http reaches only the local network or a VPN: Android allows cleartext to every host or to
 * none, and a password or token must never cross the internet unencrypted. Checked where a socket
 * connects, which every call passes, WebSockets included (OkHttp runs no network interceptor for
 * those). Plain http goes direct: through a proxy the check would see the proxy's address, and the
 * proxy would carry the token on in cleartext. [cleartext] says whether the connection about to
 * open is plain http. The rule of [plainHttpOnlyWhen] goes on to the new client.
 */
public fun OkHttpClient.localCleartext(cleartext: () -> Boolean = { true }): OkHttpClient {
    val permitted = (socketFactory as? PlainHttpRule)?.permitted ?: { true }
    return newBuilder()
        .proxySelector(DirectCleartext(proxySelector))
        .socketFactory(LocalCleartextSockets(cleartext, permitted))
        .build()
}

/**
 * This client with the app's rule for plain http: none leaves the phone while [permitted] says
 * no, whatever the address. Every client [localCleartext] and [forUrl] make from it keeps the rule.
 */
public fun OkHttpClient.plainHttpOnlyWhen(permitted: () -> Boolean): OkHttpClient =
    newBuilder().socketFactory(PlainHttpRule(permitted)).build()

/** This client for [url] over TLS; for plain http, one that connects only to local addresses. */
public fun OkHttpClient.forUrl(url: HttpUrl): OkHttpClient = if (url.isHttps) this else localCleartext()

/** Whether plain http may go to [address]: the local network, a VPN, or this device. */
public fun InetAddress.isLocal(): Boolean = isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress ||
    when (this) {
        // 100.64.0.0/10, where VPNs such as Tailscale put their peers.
        is Inet4Address -> address[0].toInt() == 100 && (address[1].toInt() and 0xC0) == 0x40

        // fc00::/7, unique local addresses.
        is Inet6Address -> (address[0].toInt() and 0xFE) == 0xFC

        else -> false
    }

/** Sockets as the system makes them; the client carries [permitted] on to those derived from it. */
private open class PlainHttpRule(val permitted: () -> Boolean) : SocketFactory() {
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

private class LocalCleartextSockets(private val cleartext: () -> Boolean, permitted: () -> Boolean) :
    PlainHttpRule(permitted) {
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

/** Equal for the same [proxies], so the clients [forUrl] makes share their plain-http connections. */
private data class DirectCleartext(private val proxies: ProxySelector) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> =
        if (uri.scheme == "http") listOf(Proxy.NO_PROXY) else proxies.select(uri)

    override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) =
        proxies.connectFailed(uri, address, failure)
}
