package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.hazymorning.amsel.app.Tip
import io.github.hazymorning.amsel.sample.HomeSample
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where Home says its tip: above the first section, and never in edit mode. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeTipTest {
    @get:Rule val compose = createComposeRule()

    private val gone = mutableListOf<Tip>()

    private fun show(state: HomeUiState, tip: Tip?) = compose.setContent {
        AmselTheme {
            HomeScreen(state, onOpen = {}, onMore = {}, onSeeAll = {}, onOpenWebUi = {}, tip = tip, onTipGone = {
                gone +=
                    it
            })
        }
    }

    @Test fun theTipStandsAboveTheFirstSection() {
        show(HomeSample.home, Tip.Settings)
        val tip = compose.onNodeWithText(
            "The gear in the search field opens Settings, and so does pulling Home down.",
        ).fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("Recently played").fetchSemanticsNode().boundsInRoot
        assertTrue("the tip is not above the first heading", tip.bottom < first.top)
    }

    @Test fun closingItTellsWhichTipWent() {
        show(HomeSample.home, Tip.EditHome)
        compose.onNodeWithContentDescription("Dismiss tip").performClick()
        assertEquals(listOf(Tip.EditHome), gone)
    }

    @Test fun editModeSaysNoTip() {
        show(HomeSample.editing, Tip.EditHome)
        compose.onNodeWithText("Long-press a heading to edit Home.").assertDoesNotExist()
    }

    @Test fun withoutATipHomeBeginsWithItsFirstSection() {
        show(HomeSample.home, null)
        compose.onNodeWithContentDescription("Dismiss tip").assertDoesNotExist()
    }
}
