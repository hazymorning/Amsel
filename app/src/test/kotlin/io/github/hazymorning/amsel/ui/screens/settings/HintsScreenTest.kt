package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.hazymorning.amsel.data.HINTS_ALL
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HintsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun eachMomentTurnsOffOnItsOwnAndAllOffKeepsTheirChoices() {
        var off by mutableStateOf(emptySet<String>())
        compose.setContent { AmselTheme { HintsScreen(off, onChange = { off = it }, onBack = {}) } }
        compose.onNodeWithText("Headphones connect").assertIsOn().performClick().assertIsOff()
        assertEquals(setOf("headphones"), off)
        compose.onNodeWithText("Show suggestions").performClick()
        assertEquals(setOf("headphones", HINTS_ALL), off)
        compose.onNodeWithText("Headphones connect").assertDoesNotExist()
        compose.onNodeWithText("Show suggestions").performClick()
        compose.onNodeWithText("Headphones connect").assertIsOff()
        compose.onNodeWithText("You like a song").assertIsOn()
    }
}
