package io.github.hazymorning.amsel.widget

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import io.github.hazymorning.amsel.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun aButtonsCoverHasCorners() {
        val thumb = WidgetArt.shaped(filled(Color.RED), px = 84, density = 3f)
        assertEquals(0, Color.alpha(thumb.getPixel(0, 0)))
        assertEquals(255, Color.alpha(thumb.getPixel(42, 42)))
    }

    @Test fun aPlayButtonsCoverFillsItsCell() {
        val stripes = filled(Color.RED).apply {
            for (x in 0 until 30) for (y in 0 until height) setPixel(x, y, Color.BLUE)
        }
        val face = WidgetArt.shaped(stripes, px = 132, density = 3f, height = 168)
        assertEquals(132, face.width)
        assertEquals(168, face.height)
        assertEquals(0, Color.alpha(face.getPixel(0, 0)))
        assertEquals(255, Color.alpha(face.getPixel(66, 2)))
        assertEquals(255, Color.alpha(face.getPixel(2, 84)))
        // The blue stripe at the source's left edge is cropped away.
        assertEquals(Color.RED, face.getPixel(4, 84))
    }

    @Test fun theDarkenedCoverIsTheCoverItself() {
        val blue = Color.rgb(60, 110, 200)
        val cover = WidgetArt.darkened(filled(blue, width = 640, height = 512))
        assertEquals(512, cover.bitmap.width)
        assertEquals(512, cover.bitmap.height)
        assertEquals(blue, cover.bitmap.getPixel(256, 256))
        assertFalse(cover.light)
        val accent = checkNotNull(cover.accent)
        assertTrue(ColorUtils.calculateLuminance(accent.cell) > ColorUtils.calculateLuminance(blue))
    }

    @Test fun theDarkenedLookTakesPaperOverAnyCover() {
        val ground = WidgetBackground.of(
            WidgetStyle.Darkened,
            WidgetArt.darkened(filled(Color.WHITE)),
            clearInk = false,
        )
        assertEquals(WidgetColors.Dark, ground.tone)
        assertEquals(R.drawable.widget_veil_darkened, ground.veil)
        assertEquals(WidgetColors.Light, WidgetBackground.of(WidgetStyle.Darkened, null, clearInk = false).tone)
    }

    @Test fun anEmptyCoverHasTheCoversShape() {
        val blank = WidgetArt.blank(px = 144, density = 3f)
        assertEquals(0, Color.alpha(blank.getPixel(0, 0)))
        assertEquals(Color.WHITE, blank.getPixel(72, 72))
    }

    @Test fun aColouredCoverLendsPlayItsColour() {
        for (colour in listOf(Color.rgb(60, 110, 200), Color.rgb(240, 200, 60), Color.rgb(150, 30, 40))) {
            val accent = checkNotNull(WidgetArt.cover(filled(colour)).accent)
            val hue = FloatArray(3).also { Color.colorToHSV(accent.cell, it) }[0]
            val wanted = FloatArray(3).also { Color.colorToHSV(colour, it) }[0]
            assertEquals(wanted, hue, 6f)
            assertTrue(ColorUtils.calculateContrast(accent.glyph, accent.cell) >= 3.0)
        }
    }

    @Test fun aGreyCoverLendsNone() = assertNull(WidgetArt.cover(filled(Color.rgb(128, 128, 128))).accent)
}
