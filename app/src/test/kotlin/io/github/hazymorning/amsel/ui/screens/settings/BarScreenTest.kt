package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.hazymorning.amsel.ui.navigation.BarPlace
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The bottom bar's page: a switch for each place but Home, and moves that keep the order. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BarScreenTest {
    @get:Rule val compose = createComposeRule()

    private val changes = mutableListOf<List<BarPlace>>()

    private fun show(places: List<BarPlace>) = compose.setContent {
        AmselTheme { BarScreen(places, onChange = { changes += it }, onBack = {}) }
    }

    @Test fun aSwitchTakesAPlaceOutOfTheBarAndBackInWhereItStands() {
        show(listOf(BarPlace.Home, BarPlace.Library))
        compose.onNodeWithText("Library").assertIsOn()
        compose.onNodeWithText("Search").assertIsOff().performClick()
        // Search stands third in the list, after Home and Library, so it joins the bar there.
        assertEquals(listOf(BarPlace.Home, BarPlace.Library, BarPlace.Search), changes.last())
        compose.onNodeWithText("Library").performClick()
        assertEquals(listOf(BarPlace.Home), changes.last())
    }

    @Test fun homeHasNoSwitchItAlwaysStays() {
        show(BarPlace.Default)
        compose.onNodeWithText("Always in the bar").assertExists()
        compose.onNodeWithText("Home").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
    }

    @Test fun talkBackMovesAPlace() {
        show(BarPlace.Default)
        val search = compose.onNodeWithText("Search")
        val actions = search.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        actions.first { it.label == "Move up" }.action()
        assertEquals(listOf(BarPlace.Home, BarPlace.Search, BarPlace.Speaker, BarPlace.Library), changes.last())
    }
}
