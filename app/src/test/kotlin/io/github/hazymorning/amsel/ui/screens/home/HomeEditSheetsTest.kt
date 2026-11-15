package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.ui.model.SpokenWord
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.Space
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What to show, in a sheet lower than its list: where it opens, and what stands on it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeEditSheetsTest {
    @get:Rule val compose = createComposeRule()

    private fun show(current: SectionOption) = compose.setContent {
        AmselTheme {
            Box(Modifier.height(SHEET)) {
                WhatSheetContent(current, SpokenWord(books = true, podcasts = true, newEpisodes = true)) {}
            }
        }
    }

    @Test fun theSheetOpensWithItsChosenRowInItsMiddle() {
        show(SectionOption.FavoriteAlbums)
        val row = compose.onNodeWithText("Favourite albums").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals((SHEET / 2).value, ((row.top + row.bottom) / 2).value, 1f)
    }

    @Test fun theLastChoiceOpensWholeAboveTheSheetsFoot() {
        show(SectionOption.YourPodcasts)
        val row = compose.onNodeWithText("Your podcasts").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals((SHEET - Space.Group).value, row.bottom.value, 1f)
    }

    @Test fun aChoiceNearTheTopLeavesTheSheetAtItsTitle() {
        show(SectionOption.Everything)
        compose.onNodeWithText("What to show").assertIsDisplayed()
    }

    @Test fun everyLineUnderTheTitleIsAChoice() {
        show(SectionOption.Everything)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertCountEquals(SectionOption.entries.size)
        compose.onAllNodes(isHeading()).assertCountEquals(1)
        compose.onNodeWithText("New albums").assertExists()
    }
}

private val SHEET = 480.dp
