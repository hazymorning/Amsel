package io.github.hazymorning.amsel.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ChoiceChipRowTest {
    @get:Rule val compose = createComposeRule()

    private val kinds =
        listOf("Playlists", "Albums", "Artists", "Songs", "Audiobooks", "Podcasts", "Radio", "Downloads")
    private var chosen by mutableStateOf(kinds[0])

    private fun inView(label: String): Boolean {
        val room = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val chip = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        return chip.left >= room.left && chip.right <= room.right
    }

    @Test fun aChoiceMadeElsewhereComesIntoView() {
        compose.setContent {
            AmselTheme { ChoiceChipRow(kinds, chosen, { chosen = it }, { it }, Role.Tab) }
        }
        assertTrue(inView("Playlists"))
        // Set directly, as a swipe between the library's kinds does, not by a tap on the chip.
        chosen = "Downloads"
        compose.waitForIdle()
        assertTrue("Downloads is chosen and out of sight", inView("Downloads"))
        chosen = "Albums"
        compose.waitForIdle()
        assertTrue("Albums is chosen and out of sight", inView("Albums"))
    }

    @Test fun aRowThatTakesAnothersPlaceStartsWhereThatStood() {
        var page by mutableStateOf(0)
        lateinit var state: ChipRowState
        compose.setContent {
            AmselTheme {
                state = rememberChipRowState()
                // A new row for each page, as each of the library's lists has its own header.
                key(page) { ChoiceChipRow(kinds, chosen, { chosen = it }, { it }, Role.Tab, state = state) }
            }
        }
        chosen = "Podcasts"
        compose.waitForIdle()
        val stood = state.scroll.value
        assertTrue("the row did not scroll to Podcasts", stood > 0)
        compose.mainClock.autoAdvance = false
        page = 1
        chosen = "Radio"
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the new row jumped", stood, state.scroll.value)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue("Radio is chosen and out of sight", inView("Radio"))
    }
}
