package io.github.hazymorning.amsel.ui.components

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.sample.PlayerSample
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Sizes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What plays stands on the dock's margin: its cover as far under the dock's edge as in from the
 * screen's, its skip's ink on the margin across.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DockCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun whatPlaysStandsOnTheMargin() {
        val playing = PlayerSample.nowPlaying
        compose.setContent {
            AmselTheme { Dock(playing, playing.speaker, onOpen = {}, onControl = {}, onSpeaker = {}) {} }
        }
        val dock = compose.onRoot().getUnclippedBoundsInRoot()
        // The speaker's button stands in the row's middle, which is the cover's.
        val button = compose.onNode(hasContentDescription("Choose speaker", substring = true))
            .getUnclippedBoundsInRoot()
        val coverMiddle = Keyline.Margin + Sizes.DockArt / 2
        assertEquals(coverMiddle.value, ((button.top + button.bottom) / 2 - dock.top).value, HALF_A_PIXEL)
        val next = compose.onNode(hasContentDescription("Next")).getUnclippedBoundsInRoot()
        val inkEnd = (next.left + next.right) / 2 + inkWidth(R.drawable.ic_skip_next) / 2
        assertEquals(Keyline.Margin.value, (dock.right - inkEnd).value, 1.dp.value)
    }
}

private const val HALF_A_PIXEL = 1f / 6
