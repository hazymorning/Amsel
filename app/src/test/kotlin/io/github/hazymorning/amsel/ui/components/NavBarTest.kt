package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.ThemeMode
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The speaker's dot sits on its glyph's outline, half way round the arc of its top, so it is one
 * with the glyph: not out in the corner of the glyph's box, where the round top leaves only air.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class NavBarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theSpeakersDotSitsOnItsOutline() {
        var line = Color.Unspecified
        var moss = Color.Unspecified
        compose.setContent {
            AmselTheme(mode = ThemeMode.Light) {
                val colors = MaterialTheme.colorScheme
                line = colors.onSurfaceVariant
                moss = colors.tertiary
                NavBar(Modifier.background(colors.surfaceContainerHigh)) {
                    NavBarSpeaker(SpeakerUi("kitchen", "Kitchen"), playing = true, onClick = {})
                }
            }
        }
        val pixels = compose.onNodeWithContentDescription("Kitchen", substring = true)
            .captureToImage()
            .toPixelMap()

        // The glyph's ink by its line's tone and the dot by its moss, each where it is drawn whole.
        var left = Int.MAX_VALUE
        var right = -1
        var top = Int.MAX_VALUE
        var dots = 0
        var sumX = 0.0
        var sumY = 0.0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val colour = pixels[x, y]
                if (colour.near(line)) {
                    left = minOf(left, x)
                    right = maxOf(right, x + 1)
                    top = minOf(top, y)
                }
                if (colour.near(moss)) {
                    dots++
                    sumX += x + 0.5
                    sumY += y + 0.5
                }
            }
        }
        assertTrue("no glyph drawn", right > left)
        assertTrue("no dot drawn", dots > 0)

        // On its 256 grid the glyph's ink runs from 56 to 200 across and from 16 down; the line of
        // its top's arc (centre 128, 88, radius 64) is half way round at (173.25, 42.75).
        val unit = (right - left) / 144.0
        val wantX = right - (200 - 173.25) * unit
        val wantY = top + (42.75 - 16) * unit
        val off = hypot(sumX / dots - wantX, sumY / dots - wantY) / compose.density.density
        assertTrue("the dot's centre is $off dp off the outline", off < 1.0)
    }

    private fun Color.near(other: Color): Boolean =
        abs(red - other.red) < NEAR && abs(green - other.green) < NEAR && abs(blue - other.blue) < NEAR

    private companion object {
        const val NEAR = 0.06f
    }
}
