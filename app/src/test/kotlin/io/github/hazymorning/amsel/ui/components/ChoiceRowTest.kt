package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A choice looks the same chosen or not, but for its tick: its name is never cut by it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xxhdpi")
class ChoiceRowTest {
    @get:Rule val compose = createComposeRule()

    /** The longest choice of the What sheet, chosen and not, over a short one. */
    private fun show(fontScale: Float) = compose.setContent {
        AmselTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Column {
                    ChoiceRow(LONG, selected = true, onClick = {})
                    ChoiceRow(LONG, selected = false, onClick = {})
                    ChoiceRow(SHORT, selected = false, onClick = {})
                }
            }
        }
    }

    private fun names() = compose.onAllNodesWithText(LONG, useUnmergedTree = true)

    private fun rows() = compose.onAllNodesWithText(LONG)

    @Test fun aNameHasTheSameRoomChosenOrNot() {
        show(fontScale = 1f)
        assertEquals(names()[1].getUnclippedBoundsInRoot().width, names()[0].getUnclippedBoundsInRoot().width)
        assertEquals(rows()[1].getUnclippedBoundsInRoot().height, rows()[0].getUnclippedBoundsInRoot().height)
    }

    @Test fun aNameTooLongForItsLineWrapsChosenOrNot() {
        show(fontScale = 2f)
        val oneLine = compose.onNodeWithText(SHORT).getUnclippedBoundsInRoot().height
        val chosen = rows()[0].getUnclippedBoundsInRoot().height
        assertTrue("the chosen name is cut on one line", chosen > oneLine)
        assertEquals(rows()[1].getUnclippedBoundsInRoot().height, chosen)
        assertEquals(names()[1].getUnclippedBoundsInRoot().width, names()[0].getUnclippedBoundsInRoot().width)
    }
}

private const val LONG = "Zuletzt gehörte Playlists"
private const val SHORT = "Reihe"
