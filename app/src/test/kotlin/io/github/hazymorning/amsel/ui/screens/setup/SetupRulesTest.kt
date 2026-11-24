package io.github.hazymorning.amsel.ui.screens.setup

import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.Schema
import io.github.hazymorning.amsel.api.ServerAddress
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.api.ServerProbe
import io.github.hazymorning.amsel.api.SignInResult
import java.io.IOException
import java.security.KeyStoreException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupRulesTest {
    private fun address(text: String) = requireNotNull(ServerAddress.parse(text))

    private fun info(schema: Int = 99) =
        ServerInfo(serverId = "m1", serverVersion = "2.11.0", schemaVersion = schema, name = "Music Assistant")

    @Test fun `addresses show their host only`() {
        assertEquals("192.168.1.20", hostOf(address("192.168.1.20")))
        assertEquals("music.local", hostOf(address("http://music.local:9000")))
        assertEquals("music.example.com", hostOf(address("https://music.example.com")))
    }

    @Test fun `an address is filled in as a person would type it, losing nothing`() {
        assertEquals("192.168.1.20", typedForm(requireNotNull(ServerAddress.fromBaseUrl("http://192.168.1.20:8095"))))
        assertEquals("http://music.local:9000", typedForm(address("music.local:9000")))
        // A name off the home network defaults to https, so the scheme can be left out.
        assertEquals("music.example.com", typedForm(address("https://music.example.com")))
        assertEquals("http://music.example.com:8095", typedForm(address("http://music.example.com:8095")))
        listOf("music.local", "music.local:9000", "https://music.example.com", "http://10.0.0.5:8095/ma").forEach {
            val typed = address(it)
            assertEquals(typed, ServerAddress.parse(typedForm(typed)))
        }
    }

    @Test fun `checking a server sorts every outcome into a plain answer`() = runBlocking {
        val here = address("music.local")
        val ready = checkServer(here) { ServerProbe(info(), emptyList()) }
        assertTrue(ready is ServerCheck.Ready)
        assertEquals(
            ServerCheck.Failed(ServerError.TooOld),
            checkServer(here) { ServerProbe(info(schema = Schema.MIN_SUPPORTED - 1), emptyList()) },
        )
        assertEquals(
            ServerCheck.Failed(ServerError.Unreachable),
            checkServer(here) { throw MaConnectionException("refused") },
        )
        assertEquals(ServerCheck.NeedsSetup, checkServer(here) { throw MaCommandException("connection", 503, "") })
        assertEquals(
            ServerCheck.Failed(ServerError.Unreachable),
            checkServer(here) { throw MaCommandException("auth/providers", 999, "") },
        )
    }

    @Test fun `a certificate the phone does not trust is said so`() = runBlocking {
        val forged = SSLHandshakeException("Handshake failed").apply { initCause(CertificateException("Untrusted")) }
        assertEquals(
            ServerCheck.Failed(ServerError.Certificate),
            checkServer(address("music.example.com")) { throw MaConnectionException("Connection failed", forged) },
        )
    }

    @Test fun `a name typed without a scheme that https does not reach asks for http, typed so`() {
        fun typed(text: String) = ServerCheckRequest(address(text), connectingTo = text, field = text, fromList = false)
        assertEquals(ServerError.HttpsFailed, ServerError.Unreachable.of(typed("music.example.com")))
        assertEquals(ServerError.Unreachable, ServerError.Unreachable.of(typed("https://music.example.com")))
        assertEquals(ServerError.Unreachable, ServerError.Unreachable.of(typed("192.168.1.20")))
        assertEquals(ServerError.Certificate, ServerError.Certificate.of(typed("music.example.com")))
    }

    @Test fun `a token that cannot be kept counts as not reaching the server`() = runBlocking {
        assertTrue(attempt { throw KeyStoreException("keystore") } is SignInResult.Failed)
        assertTrue(attempt { throw IOException("disk full") } is SignInResult.Failed)
        assertEquals(SignInResult.WrongPassword, attempt { SignInResult.WrongPassword })
    }
}
