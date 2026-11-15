package io.github.hazymorning.amsel.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixed palette keeps WCAG AA on every surface it can sit on. */
class PaletteContrastTest {
    private val schemes = mapOf("day" to LightColors, "night" to DarkColors)

    private fun ColorScheme.surfaces(): List<Pair<String, Color>> = listOf(
        "background" to surface,
        "surface 1" to surfaceContainerLow,
        "surface 2" to surfaceContainerHigh,
    )

    private fun check(what: String, ratio: Float, minimum: Float) =
        assertTrue("$what is %.2f:1, needs %.1f:1".format(ratio, minimum), ratio >= minimum)

    @Test fun textReadsOnEverySurface() = schemes.forEach { (mode, s) ->
        s.surfaces().forEach { (name, bg) -> check("$mode ink on $name", contrast(s.onSurface, bg), 7f) }
        // Secondary text stands on the page and on cards; the second tone carries only ink labels.
        listOf("background" to s.surface, "surface 1" to s.surfaceContainerLow).forEach { (name, bg) ->
            check("$mode secondary on $name", contrast(s.onSurfaceVariant, bg), 4.5f)
        }
    }

    @Test fun inkIsSoftNeverPure() = schemes.forEach { (mode, s) ->
        assertTrue("$mode ink is pure", s.onSurface != Color.White && s.onSurface != Color.Black)
        assertTrue("$mode page is pure", s.surface != Color.White && s.surface != Color.Black)
        // Linen on near-black: plenty to read, without the glare of white on black.
        assertTrue("$mode ink glares", contrast(s.onSurface, s.surface) < 17f)
    }

    @Test fun inkFillCarriesItsGlyph() = schemes.forEach { (mode, s) ->
        check("$mode play glyph on the ink circle", contrast(s.onPrimary, s.primary), 4.5f)
        check("$mode selected chip's label", contrast(s.onSurface, s.surfaceContainerHigh), 7f)
    }

    @Test fun quietGlyphsAndMossAreVisible() = schemes.forEach { (mode, s) ->
        // Faint is for glyphs and separators only, never reading text: a control's 3:1.
        check("$mode faint on the page", contrast(s.outline, s.surface), 3f)
        listOf("background" to s.surface, "surface 1" to s.surfaceContainerLow).forEach { (name, bg) ->
            check("$mode moss on $name", contrast(s.tertiary, bg), 3f)
        }
    }

    @Test fun errorsRead() = schemes.forEach { (mode, s) ->
        s.surfaces().forEach { (name, bg) -> check("$mode error on $name", contrast(s.error, bg), 4.5f) }
    }

    @Test fun surfacesStepAwayFromThePage() = schemes.forEach { (mode, s) ->
        // Layers are separated by tone, never by shadow: each surface differs from the page.
        assertTrue("$mode surface 1 equals the page", s.surfaceContainerLow != s.surface)
        assertTrue("$mode surface 2 equals surface 1", s.surfaceContainerHigh != s.surfaceContainerLow)
    }
}
