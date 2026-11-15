package io.github.hazymorning.amsel.testing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult

/**
 * Paints `sample:` artwork URLs with public-domain paintings from test resources (covers/, see its
 * CREDITS.txt), so screenshots look like a real library without network. A key with no file of its
 * own takes one of its kind's by hash, then any.
 */
class SampleCovers : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val key = chain.request.data.toString().removePrefix("sample:").substringBefore('?').replace('/', '_')
        val bitmap = load(key) ?: fallback(key)
        return SuccessResult(image = bitmap.asImage(), request = chain.request, dataSource = DataSource.MEMORY_CACHE)
    }

    private fun fallback(key: String): Bitmap {
        val kind = key.substringBefore('_')
        val pool = NAMES.filter { it.startsWith(kind) }.ifEmpty { NAMES }
        val hash = key.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
        return checkNotNull(load(pool[hash % pool.size]))
    }

    private fun load(name: String): Bitmap? = CACHE.getOrPut(name) {
        javaClass.classLoader?.getResourceAsStream("covers/$name.jpg")?.use { BitmapFactory.decodeStream(it) }
    }

    private companion object {
        val NAMES = listOf(
            "album_1", "album_2", "album_3", "album_4", "album_5", "album_6", "album_7", "album_8", "album_9",
            "album_10", "album_11", "artist_1", "artist_2", "artist_3", "artist_4", "artist_11",
            "playlist_1", "playlist_2", "playlist_3", "playlist_4", "playlist_5", "playlist_6", "playlist_7",
            "audiobook_1", "audiobook_2", "audiobook_3", "audiobook_4", "podcast_1", "podcast_2", "podcast_3",
        )
        val CACHE = mutableMapOf<String, Bitmap?>()
    }
}
