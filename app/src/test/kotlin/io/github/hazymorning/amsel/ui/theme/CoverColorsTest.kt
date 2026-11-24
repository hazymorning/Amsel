package io.github.hazymorning.amsel.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import io.github.hazymorning.amsel.testing.SampleCovers
import io.github.hazymorning.amsel.ui.model.ArtRef
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverColorsTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(DelicateCoilApi::class)
    @Before
    fun loader() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components {
                add(SampleCovers())
            }.coroutineContext(Dispatchers.Unconfined).build(),
        )
    }

    @OptIn(DelicateCoilApi::class)
    @After
    fun reset() = SingletonImageLoader.reset()

    @Test fun takesTheCoversHue() {
        var colors: CoverColors? = null
        compose.setContent { colors = rememberCoverColors(ArtRef("sample:album/1")) }
        compose.waitForIdle()
        assertNotNull("No hue from the sample cover", colors)
    }

    @Test fun noCoverLendsNothing() {
        var colors: CoverColors? = CoverColors(0f, 1f)
        compose.setContent { colors = rememberCoverColors(null) }
        compose.waitForIdle()
        assertEquals(null, colors)
    }

    @Test fun aTintedPageStillReadsAsThePageAndCarriesItsText() {
        for ((night, s) in listOf(false to LightColors, true to DarkColors)) {
            for (seed in seeds) {
                val c = coverColorsOf(seed)
                val tinted = c.tint(s.surface, night)
                assertTrue("tint of $seed night=$night strays", contrast(tinted, s.surface) < 1.3f)
                assertTrue("ink on tint of $seed night=$night", contrast(s.onSurface, tinted) >= 7f)
                assertTrue("secondary on tint of $seed night=$night", contrast(s.onSurfaceVariant, tinted) >= 4.5f)
            }
        }
    }
}

private val seeds = listOf(
    Color(0xFFFFEB3B),
    Color(0xFF0D47A1),
    Color(0xFFE53935),
    Color(0xFF00E676),
    Color(0xFF7A4E2D),
    Color(0xFF6D597A),
    Color(0xFF00BCD4),
    Color(0xFF2F4858),
)

private fun coverColorsOf(seed: Color): CoverColors = seed.hsl().let { (hue, saturation) ->
    CoverColors(hue, saturation)
}

private fun Color.hsl(): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }

/** WCAG contrast ratio of two opaque colours. */
internal fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}
