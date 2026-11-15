package io.github.hazymorning.amsel.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where the server lives. Accepts what people type: `192.168.1.20`, `music.local`,
 * `music.local:8095`, `https://music.example.com`, `http://10.0.0.5:8095/`. Without a scheme it
 * adds `http://` and port 8095; with one it keeps the scheme's default port.
 */
public data class ServerAddress(val baseUrl: HttpUrl) {
    /** The command WebSocket: `<base>/ws`. */
    public val webSocketUrl: String get() = socketUrl("ws")

    /** The Sendspin WebSocket for "This phone": `<base>/sendspin`. */
    public val sendspinUrl: String get() = socketUrl("sendspin")

    private fun socketUrl(segment: String): String = baseUrl.newBuilder().addPathSegment(segment).build().toString()
        .replaceFirst("http://", "ws://").replaceFirst("https://", "wss://")

    override fun toString(): String = baseUrl.toString().trimEnd('/')

    public companion object {
        public const val DEFAULT_PORT: Int = 8095

        /** Parses typed input; null when it cannot be an address. */
        public fun parse(input: String): ServerAddress? {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val hasScheme = trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)
            val url = if (hasScheme) {
                trimmed.toHttpUrlOrNull()
            } else {
                val withScheme = "http://$trimmed".toHttpUrlOrNull() ?: return null
                val explicitPort = Regex("^(\\[[^\\]]+]|[^:/\\[]+):\\d+").containsMatchIn(trimmed)
                if (explicitPort) withScheme else withScheme.newBuilder().port(DEFAULT_PORT).build()
            } ?: return null
            if (url.host.isBlank()) return null
            return ServerAddress(url.newBuilder().query(null).fragment(null).build())
        }

        /** From a discovered or stored base URL such as `http://192.168.1.20:8095`. */
        public fun fromBaseUrl(url: String): ServerAddress? =
            url.trimEnd('/').toHttpUrlOrNull()?.let { ServerAddress(it) }
    }
}
