package io.github.hazymorning.amsel.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import coil3.ImageLoader
import coil3.disk.DiskCache
import io.github.hazymorning.amsel.api.ImageUrls
import io.github.hazymorning.amsel.api.ServerAddress
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ArtworkProviderTest {
    @get:Rule val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    private fun ParcelFileDescriptor.bytes() = ParcelFileDescriptor.AutoCloseInputStream(this).use { it.readBytes() }

    private fun withServer(block: suspend (MockWebServer, ImageUrls, ImageLoader) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val images = ImageUrls(requireNotNull(ServerAddress.parse(server.url("/").toString())))
            val loader = ImageLoader.Builder(context)
                .diskCache { DiskCache.Builder().directory(folder.root.toOkioPath()).build() }
                .build()
            block(server, images, loader)
        }
    }

    @Test fun theCarGetsTheServersJpegFetchedOnceThenFromTheCache() = withServer { server, images, loader ->
        val jpeg = ByteArrayOutputStream().also {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        server.enqueue(MockResponse.Builder().body(Buffer().write(jpeg)).build())
        assertArrayEquals(jpeg, carCover(context, loader, images, "c0ffee")?.bytes())
        assertEquals("/imageproxy/c0ffee?size=256&fmt=jpeg", server.takeRequest().target)
        assertArrayEquals(jpeg, carCover(context, loader, images, "c0ffee")?.bytes())
        assertEquals(1, server.requestCount)
    }

    @Test fun anImageTheServerDoesNotHaveIsNone() = withServer { server, images, loader ->
        server.enqueue(MockResponse.Builder().code(404).build())
        assertNull(carCover(context, loader, images, "gone"))
    }
}
