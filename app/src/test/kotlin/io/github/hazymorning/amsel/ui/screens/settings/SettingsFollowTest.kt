package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Follow the music's speakers: all in at first, any can be left out, the last one in stays. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsFollowTest {
    @get:Rule val compose = createComposeRule()

    private val speakers = listOf(
        FollowSpeaker("gandalf", "Gandalf"),
        FollowSpeaker("hedwig", "Hedwig"),
        FollowSpeaker("potter", "Potterbox"),
    )

    @Test fun leavingAllButOneOutFollowsThatOneAlone() {
        var skipped by mutableStateOf(emptySet<String>())
        compose.setContent { AmselTheme { FollowSpeakers(speakers, skipped, RectangleShape) { skipped = it } } }
        compose.onNodeWithText("Gandalf").assertIsSelected()
        compose.onNodeWithText("Hedwig").performClick().assertIsNotSelected()
        compose.onNodeWithText("Potterbox").performClick()
        assertEquals(setOf("hedwig", "potter"), skipped)
        // Gandalf alone is followed, so it stays in.
        compose.onNodeWithText("Gandalf").performClick().assertIsSelected()
        assertEquals(setOf("hedwig", "potter"), skipped)
        compose.onNodeWithText("Hedwig").performClick().assertIsSelected()
        assertEquals(setOf("potter"), skipped)
    }
}
