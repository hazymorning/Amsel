package io.github.hazymorning.amsel.widget

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetArtTest {
    private fun filled(color: Int, width: Int = 300, height: Int = 300) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun inkStandsOnALightCover() = assertTrue(WidgetArt.cover(filled(Color.rgb(230, 228, 220))).light)

    @Test fun paperStandsOnADarkCover() = assertFalse(WidgetArt.cover(filled(Color.rgb(30, 40, 35))).light)

    /** Ink reads on a mid grey under the light veil better than paper does under the dark one. */
    @Test fun aMidGreyTakesInk() = assertTrue(WidgetArt.cover(filled(Color.rgb(128, 128, 128))).light)

    @Test fun theBlurredCoverIsSmallAndSquare() {
        val cover = WidgetArt.cover(filled(Color.BLUE, width = 640, height = 360))
        assertEquals(WidgetArt.SIDE, cover.bitmap.width)
        assertEquals(WidgetArt.SIDE, cover.bitmap.height)
    }

    @Test fun theBlurSoftensAnEdge() {
        val half = filled(Color.WHITE).apply {
            for (x in 0 until width / 2) for (y in 0 until height) setPixel(x, y, Color.BLACK)
        }
        val blurred = WidgetArt.cover(half).bitmap
        val middle = Color.red(blurred.getPixel(WidgetArt.SIDE / 2, WidgetArt.SIDE / 2))
        assertTrue("A grey between, not a hard edge: $middle", middle in 60..200)
    }

    /** A button's cover keeps the cover corners: transparent at its very corner, the picture inside. */
    @Test fun aButtonsCoverHasCorners() {
        val thumb = WidgetArt.shaped(filled(Color.RED), px = 84, density = 3f)
        assertEquals(0, Color.alpha(thumb.getPixel(0, 0)))
        assertEquals(255, Color.alpha(thumb.getPixel(42, 42)))
    }

    /** An empty cover is the cover's shape in white, for the widget to tint in its tone. */
    @Test fun anEmptyCoverHasTheCoversShape() {
        val blank = WidgetArt.blank(px = 144, density = 3f)
        assertEquals(0, Color.alpha(blank.getPixel(0, 0)))
        assertEquals(Color.WHITE, blank.getPixel(72, 72))
    }
}
