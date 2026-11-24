package io.github.hazymorning.amsel.app

import coil3.intercept.Interceptor
import coil3.request.ImageResult

// Keyed from imageproxy/ on, so a cached cover is found again under another server address (VPN, new IP).
internal fun artworkKey(url: String): String =
    url.substringAfter("/imageproxy/", missingDelimiterValue = "").takeIf { it.isNotEmpty() }
        ?.let { "imageproxy/$it" } ?: url

internal object ArtworkKeys : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed()
        if (request.diskCacheKey != null) return chain.proceed()
        return chain.withRequest(request.newBuilder().diskCacheKey(artworkKey(url)).build()).proceed()
    }
}
