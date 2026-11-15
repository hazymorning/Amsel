package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.Sizes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The band a finger shows on a row, on a phone 411 dp wide: 16 from words and glyphs, 8 from a
 * cover, and whatever stands in the row on the margin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ListRowBandTest {
    @get:Rule val compose = createComposeRule()

    private fun show() = compose.setContent {
        AmselTheme {
            Column {
                ListRow("Words", onClick = {})
                ActionRow(R.drawable.ic_remove, "A glyph", {})
                ActionRow(R.drawable.ic_remove, "A glyph among covers", {}, lead = RowLead.GlyphUnderCover)
                ListRow(
                    "A cover",
                    lead = RowLead.Cover,
                    onClick = {},
                    leading = { Box(Modifier.size(Sizes.RowArt).testTag(COVER)) },
                )
            }
        }
    }

    /** The row's band: what takes its touch. */
    private fun band(row: String) = compose.onNodeWithText(row).getUnclippedBoundsInRoot()

    @Test fun aBandKeepsSixteenFromWordsAndGlyphs() {
        show()
        for (row in listOf("Words", "A glyph")) {
            assertEquals(row, MARGIN - 16f, band(row).left.value, HALF_A_PIXEL)
            assertEquals(row, WIDTH - MARGIN + 16f, band(row).right.value, HALF_A_PIXEL)
        }
    }

    @Test fun aBandKeepsEightFromACoverAndFromAGlyphListedWithCovers() {
        show()
        for (row in listOf("A cover", "A glyph among covers")) {
            assertEquals(row, MARGIN - 8f, band(row).left.value, HALF_A_PIXEL)
            assertEquals(row, WIDTH - MARGIN + 8f, band(row).right.value, HALF_A_PIXEL)
        }
    }

    @Test fun wordsAndCoversStandOnTheMarginWhateverTheirBand() {
        show()
        val words = compose.onNodeWithText("Words", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(MARGIN, words.left.value, A_SIDE_BEARING)
        val cover = compose.onNodeWithTag(COVER, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(MARGIN, cover.left.value, HALF_A_PIXEL)
    }
}

private const val COVER = "cover"
private const val WIDTH = 411f
private const val MARGIN = 24f
private const val HALF_A_PIXEL = 1f / 6

/** A first letter's ink stands on the margin; its box begins a pixel or two before. */
private const val A_SIDE_BEARING = 0.7f
