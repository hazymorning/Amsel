package io.github.hazymorning.amsel.api

import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Whether [host] is on the home network by its form alone: an IP literal, a name without a dot, or
 * one under a suffix only home networks use. DNS is never asked: on a hostile network it answers
 * any name with a private address.
 */
public fun isLanHost(host: String): Boolean {
    val name = host.lowercase().trimEnd('.').removePrefix("[").removeSuffix("]")
    return ':' in name || IPV4.matches(name) || '.' !in name || LAN_SUFFIXES.any(name::endsWith)
}

/**
 * Whether a connection failed on the server's certificate: TLS answered with one this phone does
 * not trust, or one for another name. A server that does not speak TLS fails otherwise, with an
 * SSLException all the same: Audiobookshelf answers a TLS hello with a plain 400.
 */
public fun Throwable.isCertificateError(): Boolean =
    generateSequence(this) { it.cause }.any { it is CertificateException || it is SSLPeerUnverifiedException }

private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

private val LAN_SUFFIXES = listOf(".local", ".lan", ".internal", ".home.arpa", ".fritz.box", ".home", ".localdomain")
