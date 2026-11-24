package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PageTopBarTest {
    @get:Rule val compose = createComposeRule()

    private fun show(width: Dp, fontScale: Float) = compose.setContent {
        AmselTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Box(Modifier.width(width)) {
                    PageTopBar(TITLE, onBack = null, shorterTitle = "Bearbeiten") {
                        Box(Modifier.testTag(DONE)) { TextAction(DONE, {}) }
                    }
                }
            }
        }
    }

    private fun room(): DpRect = compose.onNodeWithText(TITLE).getUnclippedBoundsInRoot()

    /** TextAction's layout is its visible text, so these are its letters' bounds. */
    private fun done(): DpRect = compose.onNodeWithTag(DONE).getUnclippedBoundsInRoot()

    @Test fun theTitleStandsInTheBarsMiddleWhileItFitsThere() {
        show(NARROW, fontScale = 1f)
        assertEquals(NARROW.value, (room().left + room().right).value, A_PIXEL)
        assertTrue("the title's room reaches the word", done().left - room().right >= Space.Sibling - A_PIXEL.dp)
    }

    @Test fun aTitleTooLongForTheMiddleStandsInTheRoomBesideTheWord() {
        show(NARROW, fontScale = 2f)
        assertEquals(Keyline.Margin.value, room().left.value, A_PIXEL)
        assertEquals(Space.Sibling.value, (done().left - room().right).value, A_PIXEL)
    }

    @Test fun onAWiderPhoneTheShorterTitleStillStandsInTheMiddle() {
        show(WIDE, fontScale = 2f)
        assertEquals(WIDE.value, (room().left + room().right).value, A_PIXEL)
    }
}

private const val TITLE = "Startseite bearbeiten"
private const val DONE = "Fertig"
private val NARROW = 360.dp
private val WIDE = 411.dp
private const val A_PIXEL = 0.34f
