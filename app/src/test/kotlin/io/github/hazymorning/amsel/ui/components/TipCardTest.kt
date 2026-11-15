package io.github.hazymorning.amsel.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A tip goes by a swipe to either side, by its close glyph or by TalkBack, and stays after a nudge. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TipCardTest {
    @get:Rule val compose = createComposeRule()

    private var gone = 0
    private val tip = "Pull Home down to open Settings."

    private fun show() = compose.setContent { AmselTheme { TipCard(tip, onGone = { gone++ }) } }

    @Test fun aSwipeToTheRightPutsItAway() {
        show()
        compose.onNodeWithText(tip).performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(1, gone)
    }

    @Test fun aSwipeToTheLeftPutsItAway() {
        show()
        compose.onNodeWithText(tip).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, gone)
    }

    @Test fun aNudgeLeavesIt() {
        show()
        compose.onNodeWithText(tip)
            .performTouchInput { swipeRight(startX = centerX, endX = centerX + 60f, durationMillis = 600) }
        compose.waitForIdle()
        assertEquals(0, gone)
    }

    @Test fun itsCloseGlyphPutsItAway() {
        show()
        compose.onNodeWithContentDescription("Dismiss tip").performClick()
        assertEquals(1, gone)
    }

    @Test fun talkBackCanDismissIt() {
        show()
        compose.onNodeWithText(tip).performSemanticsAction(SemanticsActions.Dismiss)
        assertEquals(1, gone)
    }
}
