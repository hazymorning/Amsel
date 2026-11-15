package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.ThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every cover takes the one corner rule at its size, whatever it shows: no artwork is ever a circle. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CoverShapeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noKindOfArtworkIsCutRound() {
        compose.setContent {
            AmselTheme(mode = ThemeMode.Light) {
                Column {
                    MediaKind.entries.forEach { kind -> Artwork(null, Modifier.size(Side).testTag(kind.name), kind) }
                }
            }
        }
        val inset = (Inset.value * compose.density.density).toInt()
        MediaKind.entries.forEach { kind ->
            val pixels = compose.onNodeWithTag(kind.name).captureToImage().toPixelMap()
            // A circle leaves this point near the corner empty; the cover's corners keep it.
            assertTrue("$kind artwork is cut round", pixels[inset, inset].alpha > 0.9f)
        }
    }
}

private val Side = 72.dp
private val Inset = 8.dp
