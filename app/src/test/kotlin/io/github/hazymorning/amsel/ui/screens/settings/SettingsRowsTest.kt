package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
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
class SettingsRowsTest {
    @get:Rule val compose = createComposeRule()

    private var signedOut = 0

    private fun show(user: String) = compose.setContent {
        AmselTheme {
            SettingsScreen(
                SettingsUiState(userName = user, serverName = "Home", serverAddress = "192.168.1.20", appVersion = "1"),
                onBack = {},
                onOpenWebUi = {},
                onSignOut = { signedOut++ },
            )
        }
    }

    private fun bounds(text: String): DpRect = compose.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot()

    @Test fun signOutStandsBesideAnAccountWhoseWordsFit() {
        show("Anna")
        val name = bounds("Signed in as Anna")
        val button = bounds("Sign out")
        assertTrue("beside", button.left > name.right && button.top < name.bottom)
        compose.onNodeWithText("Sign out").performClick()
        assertEquals(0, signedOut)
        compose.onNodeWithText("Sign out?").assertExists()
        compose.onAllNodesWithText("Sign out").onLast().performClick()
        compose.waitForIdle()
        assertEquals(1, signedOut)
    }

    @Test fun signOutTakesARowOfItsOwnRatherThanBreakAWordOfTheAccount() {
        val account = "maximilian.mustermann@musterstadt.example"
        show(account)
        val name = bounds("Signed in as $account")
        val button = bounds("Sign out")
        assertTrue("under", button.top > name.bottom)
        assertEquals("from the row's start", name.left.value, button.left.value, 1f)
        compose.onNodeWithText("Sign out").performClick()
        assertEquals(0, signedOut)
        compose.onNodeWithText("Sign out?").assertExists()
        compose.onAllNodesWithText("Sign out").onLast().performClick()
        compose.waitForIdle()
        assertEquals(1, signedOut)
    }
}
