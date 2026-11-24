package io.github.hazymorning.amsel.api

import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * By the name's form alone: an IP literal, a name without a dot, or a home-network suffix. DNS is
 * not asked, as on a hostile network it can answer any name with a private address.
 */
public fun isLanHost(host: String): Boolean {
    val name = host.lowercase().trimEnd('.').removePrefix("[").removeSuffix("]")
    return ':' in name || IPV4.matches(name) || '.' !in name || LAN_SUFFIXES.any(name::endsWith)
}

/**
 * An untrusted certificate or one for another name. Not any SSLException: a plain-http server such
 * as Audiobookshelf answers a TLS hello with a 400, which fails as one too.
 */
public fun Throwable.isCertificateError(): Boolean =
    generateSequence(this) { it.cause }.any { it is CertificateException || it is SSLPeerUnverifiedException }

private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

private val LAN_SUFFIXES = listOf(".local", ".lan", ".internal", ".home.arpa", ".fritz.box", ".home", ".localdomain")
