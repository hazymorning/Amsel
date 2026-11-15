package io.github.hazymorning.amsel.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Artwork URLs: always on the address the app connected with, never on
 * `server_info.base_url`. No auth is needed. The size is added with [sized] once it is known.
 */
public class Images(private val address: ServerAddress) {
    /** `<base>/imageproxy/<proxy_id>` without a size, or null when there is no proxied image. */
    public fun url(image: MediaItemImage?): String? = image?.proxyId?.let(::url)

    /** `<base>/imageproxy/[proxyId]` without a size. */
    public fun url(proxyId: String): String =
        address.baseUrl.newBuilder().addPathSegment("imageproxy").addPathSegment(proxyId).build().toString()

    /**
     * Re-homes an absolute `current_media.image_url` onto the connection's address. The server
     * hands out an outside source's own image address as it is (a streaming service's artwork):
     * that is null here, since nothing is fetched from anywhere but the server.
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

        /**
         * [url] at the smallest size the proxy serves that is at least [px] wide; the original above
         * 1024. In the source's own type unless [format] (`fmt`) asks for another.
         */
        public fun sized(url: String, px: Int, format: String? = null): String {
            val size = SIZES.firstOrNull { it >= px } ?: 0
            return if (format == null) "$url?size=$size" else "$url?size=$size&fmt=$format"
        }
    }
}
