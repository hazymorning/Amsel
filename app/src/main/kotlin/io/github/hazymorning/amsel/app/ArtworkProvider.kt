package io.github.hazymorning.amsel.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.hazymorning.amsel.api.ImageUrls
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.playback.CarArtwork
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking

// The car asks on a binder thread, so openFile may block; it shows a placeholder until the file is there.
class ArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = context ?: throw FileNotFoundException(uri.toString())
        val images = context.graph.data.session.images
        val id = CarArtwork.proxyId(uri)
        if (images == null || id == null) throw FileNotFoundException(uri.toString())
        return runBlocking { carCover(context, context.graph.imageLoader, images, id) }
            ?: throw FileNotFoundException(uri.toString())
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        0
}

// A JPEG, as the server sends an SVG unchanged otherwise. Always through the loader, not straight
// from the disk cache, which keeps the server's refusals too.
internal suspend fun carCover(
    context: Context,
    loader: ImageLoader,
    images: ImageUrls,
    proxyId: String,
): ParcelFileDescriptor? {
    val url = ImageUrls.sized(images.url(proxyId), CarArtwork.SIZE, format = "jpeg")
    val key = artworkKey(url)
    val request = ImageRequest.Builder(
        context,
    ).data(url).diskCacheKey(key).memoryCachePolicy(CachePolicy.DISABLED).build()
    if (loader.execute(request) !is SuccessResult) return null
    // Opened while the snapshot is held, so an eviction cannot delete the file first.
    return loader.diskCache?.openSnapshot(key)?.use {
        ParcelFileDescriptor.open(it.data.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
