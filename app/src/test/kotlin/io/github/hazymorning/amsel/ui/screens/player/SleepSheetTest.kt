package io.github.hazymorning.amsel.ui.screens.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.test.core.app.ApplicationProvider
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.SleepMode
import io.github.hazymorning.amsel.app.SleepTimerState
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sleep timer's sheet holds still: what it says changes, where things stand does not. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SleepSheetTest {
    @get:Rule val compose = createComposeRule()

    private var timer by mutableStateOf<SleepTimerState?>(null)

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val turnOff = resources.getString(R.string.sleep_turn_off)

    private fun show() = compose.setContent {
        AmselTheme { SleepSheetContent("Kitchen", timer, R.string.sleep_end_of_chapter, onSet = {}, onOff = {}) }
    }

    /** Off, the first and the last preset, and the title. */
    private fun places(): List<DpRect> {
        val presets = listOf(15, 60).map { resources.getString(R.string.sleep_minutes, it) }
        return (listOf(turnOff) + presets).map { compose.onNodeWithContentDescription(it).getBoundsInRoot() } +
            compose.onNodeWithText("Sleep timer · Kitchen").getBoundsInRoot()
    }

    @Test fun nothingMovesWhenATimerStartsChangesOrStops() {
        show()
        val off = places()
        compose.onNodeWithText(resources.getString(R.string.sleep_off)).assertExists()
        compose.onNodeWithContentDescription(turnOff).assertIsNotEnabled()

        timer = SleepTimerState(SleepMode.Minutes(30), System.currentTimeMillis() + 30 * 60_000L)
        compose.waitForIdle()
        assertEquals("a timer runs", off, places())
        compose.onNodeWithContentDescription(turnOff).assertIsEnabled()

        timer = SleepTimerState(SleepMode.EndOfItem, endsAtMs = null)
        compose.waitForIdle()
        assertEquals("to the chapter's end", off, places())

        timer = null
        compose.waitForIdle()
        assertEquals("off again", off, places())
    }
}
