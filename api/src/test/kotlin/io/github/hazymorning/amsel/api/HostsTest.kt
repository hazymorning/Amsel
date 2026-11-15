package io.github.hazymorning.amsel.api

import java.io.IOException
import java.net.ConnectException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.assertEquals
import org.junit.Test

class HostsTest {
    @Test fun `addresses and names only a home network has are home addresses`() = listOf(
        "192.168.1.20", "10.0.0.5", "100.101.102.103", "203.0.113.7", "fd00::20", "[fd00::20]", "nas", "localhost",
        "music.local", "music.local.", "nas.lan", "ma.internal", "ma.home.arpa", "nas.fritz.box", "pi.home",
        "server.localdomain", "MUSIC.LOCAL",
    ).forEach { assertEquals(it, true, isLanHost(it)) }

    @Test fun `any other name could be answered by any DNS`() =
        listOf("music.example.com", "local.example.com", "home.example.com", "lan.party.de", "fritz.box.example.com")
            .forEach { assertEquals(it, false, isLanHost(it)) }

    @Test fun `only a certificate the phone does not trust is a certificate error`() {
        fun handshake(cause: Throwable?) = SSLHandshakeException("handshake").apply { initCause(cause) }
        val untrusted = IOException("wrapped", handshake(CertificateException("Trust anchor not found")))
        assertEquals(true, untrusted.isCertificateError())
        assertEquals(true, SSLPeerUnverifiedException("Hostname books.example.com not verified").isCertificateError())
        // A plain http server answering the TLS hello, as Audiobookshelf does with a 400.
        assertEquals(false, SSLException("Unsupported or unrecognized SSL message").isCertificateError())
        assertEquals(false, handshake(null).isCertificateError())
        assertEquals(false, ConnectException("Connection refused").isCertificateError())
    }
}
