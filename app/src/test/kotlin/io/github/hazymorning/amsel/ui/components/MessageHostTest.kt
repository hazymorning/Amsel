package io.github.hazymorning.amsel.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MessageHostTest {
    @get:Rule val compose = createComposeRule()

    private val host = SnackbarHostState()
    private lateinit var scope: CoroutineScope
    private val results = mutableListOf<SnackbarResult>()

    private fun say(text: String) {
        compose.runOnIdle { scope.launch { results += host.showSnackbar(text) } }
        compose.waitForIdle()
    }

    private fun show() = compose.setContent {
        scope = rememberCoroutineScope()
        AmselTheme { MessageHost(host) }
    }

    @Test fun aSwipeToEitherSideSendsItAway() {
        show()
        say("Couldn’t play on Kitchen")
        compose.onNodeWithText("Couldn’t play on Kitchen").performTouchInput { swipeRight() }
        compose.waitForIdle()
        say("Couldn’t reach Music Assistant")
        compose.onNodeWithText("Couldn’t reach Music Assistant").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf(SnackbarResult.Dismissed, SnackbarResult.Dismissed), results)
        compose.onNodeWithText("Couldn’t reach Music Assistant").assertDoesNotExist()
    }

    @Test fun aNudgeLeavesIt() {
        show()
        say("Couldn’t play on Kitchen")
        compose.onNodeWithText("Couldn’t play on Kitchen")
            .performTouchInput { swipeRight(startX = centerX, endX = centerX + 60f, durationMillis = 600) }
        compose.waitForIdle()
        assertEquals(emptyList<SnackbarResult>(), results)
        compose.onNodeWithText("Couldn’t play on Kitchen").assertExists()
    }
}
