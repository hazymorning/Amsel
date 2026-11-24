package io.github.hazymorning.amsel.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Through the app's image loader, so Media3's artwork shares its OkHttp client and cache.
@OptIn(UnstableApi::class)
class ArtworkLoader(private val context: Context, private val images: ImageLoader, private val scope: CoroutineScope) :
    BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = load(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = load(uri.toString())

    private fun load(data: Any): ListenableFuture<Bitmap> {
        val bitmap = SettableFuture.create<Bitmap>()
        // A notification bitmap crosses processes, which a hardware bitmap cannot.
        val request = ImageRequest.Builder(context).data(data).allowHardware(false).build()
        val job = scope.launch {
            when (val result = images.execute(request)) {
                is SuccessResult -> bitmap.set(result.image.toBitmap())
                is ErrorResult -> bitmap.setException(result.throwable)
            }
        }
        bitmap.addListener({ if (bitmap.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return bitmap
    }
}
