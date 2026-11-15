package io.github.hazymorning.amsel.api

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCleartextTest {
    private fun local(address: String) = InetAddress.getByName(address).isLocal()

    @Test fun `home networks, link-local, loopback and VPN peers are local`() = listOf(
        "192.168.1.20", "10.0.0.5", "172.16.4.1", "169.254.3.3", "127.0.0.1", "100.101.102.103",
        "fd12:3456::20", "fe80::1", "::1",
    ).forEach { assertEquals(it, true, local(it)) }

    @Test fun `the internet is not`() = listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:4860::8888")
        .forEach { assertEquals(it, false, local(it)) }

    @Test fun `plain http never connects beyond the local network, WebSockets and proxies included`() {
        val remote = requireNotNull(ServerAddress.parse("203.0.113.7"))
        listOf(OkHttpClient(), OkHttpClient.Builder().proxySelector(LocalProxy).build()).forEach { client ->
            val call = client.forUrl(remote.baseUrl).newCall(Request.Builder().url(remote.baseUrl).build())
            assertTrue(refused(assertThrows(IOException::class.java) { call.execute() }))
            val socket = assertThrows(MaConnectionException::class.java) {
                runBlocking { MaSocket(client, remote).open() }
            }
            assertTrue(refused(socket))
        }
    }

    /** A proxy on this device, as a network's settings or a PAC file give one. */
    private object LocalProxy : ProxySelector() {
        override fun select(uri: URI) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9)))

        override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
    }

    /** OkHttp and coroutines wrap the refusal, so it is looked for along the causes. */
    private fun refused(error: Throwable) =
        generateSequence(error) { it.cause }.any { it is ConnectException && it.message == REFUSED }

    private companion object {
        const val REFUSED = "Plain http only reaches the local network"
    }
}
