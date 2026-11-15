package io.github.hazymorning.amsel.data.downloads

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.Proxy
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Audiobookshelf's password and tokens may go over plain http: to a home address, typed so or
 * by its form, and only from the network where Music Assistant answered as itself.
 */
class AbsClientTest {
    private val abs = FakeAbs()
    private val accounts = MemoryAbsAccounts()
    private val port = abs.url.substringAfterLast(':')

    @After fun close() = abs.close()

    /** Its https fails with [failure] before connecting; [NAME] is on this phone, as a hostile DNS can say. */
    private fun client(
        failure: IOException = ConnectException("Connection refused"),
        reachesHome: suspend () -> Boolean = { true },
    ) = AbsClient(
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns { host -> if (host == NAME) listOf(InetAddress.getLoopbackAddress()) else Dns.SYSTEM.lookup(host) }
            .addInterceptor { chain ->
                if (chain.request().url.isHttps) throw failure
                chain.proceed(chain.request())
            }
            .build(),
        accounts,
        reachesHome,
    )

    private fun reason(block: suspend () -> Unit): AbsException.Reason? = runBlocking {
        try {
            block()
            null
        } catch (e: AbsException) {
            e.reason
        }
    }

    @Test fun `a name off the home network is not tried over plain http, wherever its DNS points`() {
        assertEquals(AbsException.Reason.HttpsFailed, reason { client().signIn("$NAME:$port", "root", "pw") })
        assertTrue(abs.requests.toString(), abs.requests.isEmpty())
        assertNull(accounts.account.value)
        // Typed with http://, it is what the person asked for.
        runBlocking { client().signIn("http://$NAME:$port", "root", "pw") }
        assertEquals("http://$NAME:$port", accounts.account.value?.url)
    }

    @Test fun `a certificate the phone does not trust ends the sign-in, at a home address too`() {
        val forged = SSLHandshakeException("Handshake failed").apply { initCause(CertificateException("Untrusted")) }
        assertEquals(AbsException.Reason.Certificate, reason { client(forged).signIn("127.0.0.1:$port", "root", "pw") })
        assertTrue(abs.requests.toString(), abs.requests.isEmpty())
    }

    @Test fun `a home server that answers TLS in plain text is signed in to over plain http`() = runBlocking {
        // Audiobookshelf answers a TLS hello with a plain 400.
        client(SSLException("Unsupported or unrecognized SSL message")).signIn("127.0.0.1:$port", "root", "pw")
        assertEquals(abs.url, accounts.account.value?.url)
    }

    @Test fun `away from home plain http sends neither password nor token`() {
        val away = client(reachesHome = { false })
        assertEquals(AbsException.Reason.AwayFromHome, reason { away.signIn(abs.url, "root", "pw") })
        runBlocking { accounts.save(AbsAccount(abs.url, "root"), abs.refreshToken) }
        assertEquals(AbsException.Reason.Unreachable, reason { away.progress("book1", null) })
        assertEquals(AbsException.Reason.Unreachable, reason { away.saveProgress("book1", null, 61.5, 200.0, false) })
        assertEquals(AbsException.Reason.Unreachable, reason { away.file("book1", "1", 0).close() })
        assertTrue(abs.requests.toString(), abs.requests.isEmpty())
        // Back home, the same calls go out.
        assertNull(runBlocking { client().progress("book1", null) })
        assertTrue(abs.requests.toString(), "POST /auth/refresh" in abs.requests)
    }

    @Test fun `an https address needs no word about home`() {
        val asked = client(reachesHome = { error("Asked about home for https") })
        runBlocking { accounts.save(AbsAccount("https://$NAME", "root"), "refresh-0") }
        assertEquals(AbsException.Reason.Unreachable, reason { asked.progress("book1", null) })
    }

    private companion object {
        const val NAME = "books.example.test"
    }
}
