package io.github.hazymorning.amsel.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MosaicCropTest {
    @Test fun aTileKeepsTwoByTwo() {
        val cover = crop(px = 150)
        assertEquals(150, cover.width)
        assertEquals(column(1), cover.getPixel(140, 10))
    }

    @Test fun aPageCoverKeepsAsManyMoreAsStaySharp() {
        val cover = crop(px = 250)
        assertEquals(250, cover.width)
        assertEquals(column(2), cover.getPixel(240, 10))
    }

    @Test fun aPictureThatIsNoGridStaysWhole() {
        val plain = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val cover = runBlocking { MosaicCrop(SIDE).transform(plain, Size.ORIGINAL) }
        assertEquals(SIDE, cover.width)
    }

    private fun crop(px: Int): Bitmap = runBlocking { MosaicCrop(px).transform(collage(), Size.ORIGINAL) }

    /** Six by six tiles, each column its own colour, light and dark in turn, as a server's collage. */
    private fun collage(): Bitmap {
        val tile = SIDE / 6
        val bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        for (x in 0 until SIDE) {
            for (y in 0 until SIDE) {
                val row = y / tile
                bitmap.setPixel(x, y, if (row % 2 == 0) column(x / tile) else Color.BLACK)
            }
        }
        return bitmap
    }

    private fun column(index: Int): Int = COLUMNS[index]

    private companion object {
        const val SIDE = 600
        val COLUMNS = intArrayOf(
            Color.WHITE,
            Color.rgb(40, 40, 40),
            Color.rgb(230, 230, 230),
            Color.rgb(20, 20, 20),
            Color.rgb(250, 250, 250),
            Color.rgb(30, 30, 30),
        )
    }
}
