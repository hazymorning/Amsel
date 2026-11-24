package io.github.hazymorning.amsel.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * On the connection's address, never `server_info.base_url`, which can be container-internal. No
 * auth is needed. The URLs come without a size: add it with [sized].
 */
public class ImageUrls(private val address: ServerAddress) {
    public fun url(image: MediaItemImage?): String? = image?.proxyId?.let(::url)

    public fun url(proxyId: String): String =
        address.baseUrl.newBuilder().addPathSegment("imageproxy").addPathSegment(proxyId).build().toString()

    /**
     * Moves an imageproxy `current_media.image_url` onto the connection's address. An outside
     * source's own image address gives null: nothing is fetched from anywhere but the server.
     */
    public fun rebase(imageUrl: String?): String? {
        val parsed = imageUrl?.toHttpUrlOrNull() ?: return null
        val proxyIndex = parsed.pathSegments.indexOf("imageproxy")
        if (proxyIndex < 0) return null
        val builder = address.baseUrl.newBuilder()
        parsed.pathSegments.drop(proxyIndex).forEach { builder.addPathSegment(it) }
        return builder.build().toString()
    }

    public companion object {
        private val SIZES = intArrayOf(80, 160, 256, 512, 1024)

        /** Rounds [px] up to a size the proxy serves (any other is HTTP 400), or above 1024 to 0, the original. */
        public fun sized(url: String, px: Int, format: String? = null): String {
            val size = SIZES.firstOrNull { it >= px } ?: 0
            return if (format == null) "$url?size=$size" else "$url?size=$size&fmt=$format"
        }
    }
}
