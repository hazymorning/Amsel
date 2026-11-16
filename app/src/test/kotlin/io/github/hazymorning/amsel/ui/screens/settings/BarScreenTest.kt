package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.hazymorning.amsel.ui.navigation.Arrangement
import io.github.hazymorning.amsel.ui.navigation.BarPlace
import io.github.hazymorning.amsel.ui.navigation.DockControl
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The arrange pages: a switch shows or hides in place, the order moves only when asked, and limits hold. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BarScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aSwitchTurnsAPlaceOffWhereItStands() {
        var places by mutableStateOf(BarPlace.arrangement(null))
        compose.setContent { AmselTheme { BarScreen(places, onChange = { places = it }, onBack = {}) } }
        val before = compose.onNodeWithText("Search").getUnclippedBoundsInRoot()
        compose.onNodeWithText("Search").assertIsOn().performClick().assertIsOff()
        // It left the bar but kept its row: nothing jumped.
        assertEquals(BarPlace.Default, places.order)
        assertEquals(listOf(BarPlace.Home, BarPlace.Speaker, BarPlace.Library), places.visible)
        assertEquals(before, compose.onNodeWithText("Search").getUnclippedBoundsInRoot())
        compose.onNodeWithText("Search").performClick()
        assertEquals(BarPlace.Default, places.visible)
    }

    @Test fun homeHasNoSwitchItAlwaysStays() {
        compose.setContent { AmselTheme { BarScreen(BarPlace.arrangement(null), onChange = {}, onBack = {}) } }
        compose.onNodeWithText("Always in the bar").assertExists()
        compose.onNodeWithText("Home").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
    }

    @Test fun talkBackMovesAPlace() {
        var places by mutableStateOf(BarPlace.arrangement(null))
        compose.setContent { AmselTheme { BarScreen(places, onChange = { places = it }, onBack = {}) } }
        val actions = compose.onNodeWithText("Search").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        actions.first { it.label == "Move up" }.action()
        assertEquals(listOf(BarPlace.Home, BarPlace.Search, BarPlace.Speaker, BarPlace.Library), places.order)
    }

    @Test fun theMiniPlayerTakesThreeAtMostAndPlayStays() {
        var controls by mutableStateOf(
            Arrangement(DockControl.entries, setOf(DockControl.PlayPause, DockControl.Next, DockControl.Previous)),
        )
        compose.setContent { AmselTheme { DockScreen(controls, onChange = { controls = it }, onBack = {}) } }
        compose.onNodeWithText("Always in the mini player").assertExists()
        // Three shown: one more waits, and TalkBack hears why.
        compose.onNodeWithText("Favourite").performClick().assertIsOff()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Three at most: turn another one off first",
                ),
            )
        assertEquals(3, controls.shown.size)
        compose.onNodeWithText("Previous").performClick()
        compose.onNodeWithText("Favourite").performClick().assertIsOn()
        assertEquals(listOf(DockControl.PlayPause, DockControl.Next, DockControl.Favorite), controls.visible)
    }
}
