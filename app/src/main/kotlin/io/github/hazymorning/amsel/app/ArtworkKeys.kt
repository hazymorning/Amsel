package io.github.hazymorning.amsel.app

import coil3.intercept.Interceptor
import coil3.request.ImageResult

/**
 * The disk cache's name for a cover: what follows `imageproxy/` in its address, so a cover kept at
 * home is found again over the VPN or a new address, where the address before it differs.
 */
internal fun artworkKey(url: String): String =
    url.substringAfter("/imageproxy/", missingDelimiterValue = "").takeIf { it.isNotEmpty() }
        ?.let { "imageproxy/$it" } ?: url

/** Gives every cover its [artworkKey] in the disk cache. */
internal object ArtworkKeys : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed()
        if (request.diskCacheKey != null) return chain.proceed()
        return chain.withRequest(request.newBuilder().diskCacheKey(artworkKey(url)).build()).proceed()
    }
}
