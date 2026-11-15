package io.github.hazymorning.amsel.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {
    private fun base(input: String) = ServerAddress.parse(input)?.toString()

    @Test fun `bare IP gets http and port 8095`() = assertEquals("http://192.168.1.20:8095", base("192.168.1.20"))

    @Test fun `bare host name gets http and port 8095`() = assertEquals(
        "http://music.local:8095",
        base(" music.local "),
    )

    @Test fun `explicit port is kept`() = assertEquals("http://music.local:9000", base("music.local:9000"))

    @Test fun `bare IPv6 gets port 8095, and keeps an explicit one`() {
        assertEquals("http://[fd00::20]:8095", base("[fd00::20]"))
        assertEquals("http://[fd00::20]:9000", base("[fd00::20]:9000"))
    }

    @Test fun `home names get http and port 8095, other names https`() {
        assertEquals("http://nas:8095", base("nas"))
        assertEquals("http://nas.fritz.box:8095", base("NAS.fritz.box"))
        assertEquals("https://music.example.com", base("music.example.com"))
        assertEquals("https://music.example.com:8443", base("music.example.com:8443"))
        assertEquals("https://music.example.com:80", base("music.example.com:80"))
    }

    @Test fun `https keeps its default port`() = assertEquals(
        "https://music.example.com",
        base("https://music.example.com/"),
    )

    @Test fun `http with port is kept`() = assertEquals("http://10.0.0.5:8095", base("http://10.0.0.5:8095/"))

    @Test fun `sub path is kept for reverse proxies`() =
        assertEquals("https://example.com/ma", base("https://example.com/ma"))

    @Test fun `garbage is rejected`() {
        assertNull(base(""))
        assertNull(base("my server"))
    }

    @Test fun `websocket urls follow the scheme`() {
        assertEquals("ws://192.168.1.20:8095/ws", ServerAddress.parse("192.168.1.20")!!.webSocketUrl)
        assertEquals("wss://example.com/ma/ws", ServerAddress.parse("https://example.com/ma")!!.webSocketUrl)
        assertEquals("wss://example.com/ma/sendspin", ServerAddress.parse("https://example.com/ma")!!.sendspinUrl)
    }
}
