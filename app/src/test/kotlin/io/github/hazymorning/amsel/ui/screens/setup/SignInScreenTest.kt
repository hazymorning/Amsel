package io.github.hazymorning.amsel.ui.screens.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTextInput
import io.github.hazymorning.amsel.sample.SetupSample
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** TalkBack, and the live first-run journey, find each field by its label. */
@RunWith(RobolectricTestRunner::class)
class SignInScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun eachFieldCarriesItsLabel() {
        var state by mutableStateOf(SetupSample.signIn)
        compose.setContent {
            AmselTheme {
                SignInScreen(
                    state,
                    onBack = {},
                    onSignInWithHomeAssistant = {},
                    onUsernameChange = { state = state.copy(username = it) },
                    onPasswordChange = { state = state.copy(password = it) },
                    onPasswordVisibleChange = {},
                    onSignIn = {},
                )
            }
        }
        compose.onNode(hasSetTextAction() and hasText("Username")).performTextInput("amsel")
        compose.onNode(hasSetTextAction() and hasText("Password")).performTextInput("secret")
        compose.runOnIdle {
            assertEquals("amsel", state.username)
            assertEquals("secret", state.password)
        }
    }
}
