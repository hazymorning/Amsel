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
 * open is plain http.
 */
public fun OkHttpClient.localCleartext(cleartext: () -> Boolean = { true }): OkHttpClient =
    newBuilder().proxySelector(DirectCleartext(proxySelector)).socketFactory(LocalCleartextSockets(cleartext)).build()

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

private class LocalCleartextSockets(private val cleartext: () -> Boolean) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val address = (endpoint as? InetSocketAddress)?.address
            if (cleartext() && address?.isLocal() != true) {
                throw ConnectException("Plain http only reaches the local network")
            }
            super.connect(endpoint, timeout)
        }
    }

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

private class DirectCleartext(private val proxies: ProxySelector) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> =
        if (uri.scheme == "http") listOf(Proxy.NO_PROXY) else proxies.select(uri)

    override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) =
        proxies.connectFailed(uri, address, failure)
}
