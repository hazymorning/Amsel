package io.github.hazymorning.amsel.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

public data class ServerAddress(val baseUrl: HttpUrl) {
    public val webSocketUrl: String get() = socketUrl("ws")

    public val sendspinUrl: String get() = socketUrl("sendspin")

    private fun socketUrl(segment: String): String = baseUrl.newBuilder().addPathSegment(segment).build().toString()
        .replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")

    override fun toString(): String = baseUrl.toString().trimEnd('/')

    public companion object {
        public const val DEFAULT_PORT: Int = 8095

        /**
         * Without a scheme a home address ([isLanHost]) gets `http://` and port 8095, any other name
         * `https://`. A typed port is always kept.
         */
        public fun parse(input: String): ServerAddress? {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val hasScheme = trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)
            val url = if (hasScheme) {
                trimmed.toHttpUrlOrNull()
            } else {
                val withScheme = "http://$trimmed".toHttpUrlOrNull() ?: return null
                val explicitPort = Regex("^(\\[[^\\]]+]|[^:/\\[]+):\\d+").containsMatchIn(trimmed)
                when {
                    // Plain http to other names only when typed: DNS on a hostile network can answer
                    // them with a private address, which the cleartext guard lets through.
                    !isLanHost(withScheme.host) -> withScheme.newBuilder()
                        .scheme("https")
                        .port(if (explicitPort) withScheme.port else HttpUrl.defaultPort("https"))
                        .build()

                    explicitPort -> withScheme

                    else -> withScheme.newBuilder().port(DEFAULT_PORT).build()
                }
            } ?: return null
            if (url.host.isBlank()) return null
            return ServerAddress(url.newBuilder().query(null).fragment(null).build())
        }

        public fun fromBaseUrl(url: String): ServerAddress? =
            url.trimEnd('/').toHttpUrlOrNull()?.let { ServerAddress(it) }
    }
}
