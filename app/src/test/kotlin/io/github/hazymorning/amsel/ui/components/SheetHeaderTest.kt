package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.Keyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A sheet's title never loses a letter to the value at its end, and holds still while the value changes. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SheetHeaderTest {
    @get:Rule val compose = createComposeRule()

    private var level by mutableStateOf("40 %")

    private fun show(name: String, width: Dp) = compose.setContent {
        AmselTheme {
            Box(Modifier.width(width)) { SheetHeader("Lautstärke", name = name, value = level, widestValue = "100 %") }
        }
    }

    private fun bounds(text: String): DpRect = compose.onNodeWithText(text).getBoundsInRoot()

    @Test fun aTitleItsSpeakerAndTheValueShareALineWhereTheyFit() {
        show("Dieses Handy", 360.dp)
        val title = bounds("Lautstärke · Dieses Handy")
        val value = bounds("40 %")
        assertTrue("one line", title.bottom - title.top < ONE_LINE * 1.5f)
        assertTrue("the value after the title", value.left > title.left && value.top < title.bottom)
        assertEquals("on the end margin", (360.dp - Keyline.Margin).value, value.right.value, 0.5f)
    }

    @Test fun aSpeakerThatDoesNotFitBesideTheValueStandsUnderTheTitleWithTheValueOnItsLine() {
        show("Schlafzimmer +2", 360.dp)
        val title = bounds("Lautstärke · Schlafzimmer +2")
        val value = bounds("40 %")
        assertTrue("two lines", title.bottom - title.top > ONE_LINE * 1.5f)
        assertTrue("the value on the last line", value.top > title.top + ONE_LINE / 2)
    }

    @Test fun theTitleHoldsStillWhileTheValueChanges() {
        show("Wohnzimmer", 411.dp)
        val quiet = bounds("Lautstärke · Wohnzimmer")
        val end = bounds("40 %").right
        level = "100 %"
        compose.waitForIdle()
        assertEquals(quiet, bounds("Lautstärke · Wohnzimmer"))
        assertEquals(end, bounds("100 %").right)
        level = "5 %"
        compose.waitForIdle()
        assertEquals(quiet, bounds("Lautstärke · Wohnzimmer"))
        assertEquals(end, bounds("5 %").right)
    }

    private companion object {
        /** A sheet title's line. */
        val ONE_LINE = 28.dp
    }
}
