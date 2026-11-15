package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import io.github.hazymorning.amsel.sample.PlayerSample
import io.github.hazymorning.amsel.ui.model.ActionsContext
import io.github.hazymorning.amsel.ui.model.ItemAction
import io.github.hazymorning.amsel.ui.model.ItemActionsUiState
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.CoverColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The endless mix in Now Playing, the DJ's sheet, and what the DJ says in Up next and Now Playing. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ExtrasScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        AmselTheme { content() }
    }

    private fun showNowPlaying(state: NowPlayingUi) = show {
        NowPlayingScreen(
            state,
            onControl = {},
            onClose = {},
            onMore = {},
            onArtist = {},
            onSpeaker = {},
            onUpNext = {},
            colors = CoverColors(hue = 205f, saturation = 0.3f),
        )
    }

    @Test fun theDjSheetIsOneChoiceAmongRowsATouchCanHit() {
        var talking by mutableStateOf<String?>("music_nerd")
        show { DjSheetContent("Kitchen", PlayerSample.djHosts, talking, onPick = { talking = it }) }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertExists()
        val choice = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        fun ticked(name: String) = SemanticsMatcher.expectValue(SemanticsProperties.Selected, name == "Music nerd")
        (listOf("Off") + PlayerSample.djHosts.map { it.name }).forEach { name ->
            val row = compose.onNodeWithText(name).assert(choice and ticked(name)).fetchSemanticsNode()
            assertTrue(name, row.size.height >= 48 * compose.density.density)
        }
        compose.onNodeWithText("Off").performClick()
        assertEquals(null, talking)
        compose.onNodeWithText("Off").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithText("Party host").performClick()
        assertEquals("party_host", talking)
    }

    @Test fun whatTheDjSaysStandsInUpNextOverDjAndOpensASheetOnlyWhileItCanGo() {
        val opened = mutableListOf<String>()
        var held by mutableIntStateOf(3)
        show {
            UpNextScreen(
                UpNextUiState(PlayerSample.upNextWithDj, firstEditable = held),
                onClose = {},
                onPlay = {},
                onMove = { _, _ -> },
                onRemove = {},
                onMore = { opened += it.id },
                onRetry = {},
            )
        }
        val words = compose.onNode(hasText("Artist fact + Transition") and hasText("DJ"))
        // The speaker already holds it: it can no longer be removed, and it has nothing else to offer.
        words.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        held = 2
        words.performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(listOf("dj"), opened)
    }

    @Test fun theSheetOfWhatTheDjSaysNamesItOverDjAndOnlyRemovesIt() {
        val tapped = mutableListOf<ItemAction>()
        show {
            ItemActionsSheetContent(
                ItemActionsUiState.forTrack(PlayerSample.djWords, ActionsContext.UpNext),
                onAction = { tapped += it },
            )
        }
        compose.onNode(hasText("Artist fact + Transition") and hasText("DJ")).assertExists()
        compose.onNodeWithText("Remove from Up next").performClick()
        compose.onNodeWithText("Play next").assertDoesNotExist()
        assertEquals(listOf(ItemAction.RemoveFromUpNext), tapped)
    }

    @Test fun whileTheDjTalksNowPlayingNamesItOverDjWithoutAHeart() {
        showNowPlaying(PlayerSample.djTalking)
        compose.onNodeWithText("Artist fact + Transition").assertExists()
        compose.onNodeWithText("DJ").assertExists()
        compose.onNodeWithContentDescription("Like").assertDoesNotExist()
        compose.onNodeWithContentDescription("More options for Artist fact + Transition").assertExists()
    }

    @Test fun anEndlessMixIsNamedAboveTheArtworkAndSaysWhyShuffleAndRepeatAreFixed() {
        showNowPlaying(PlayerSample.nowPlaying.copy(mix = "Blue Hour", canShuffle = false))
        compose.onNodeWithText("Endless mix · Blue Hour").assertExists()
        compose.onNodeWithText("from Blue Hour").assertDoesNotExist()
        val fixed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Set by the endless mix")
        compose.onNodeWithContentDescription("Shuffle").assert(fixed).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Repeat").assert(fixed).assertIsNotEnabled()
    }

    @Test fun aMixWithoutANameIsStillAMix() {
        showNowPlaying(PlayerSample.nowPlaying.copy(mix = "", canShuffle = false))
        compose.onNodeWithText("Endless mix").assertExists()
    }

    @Test fun anotherQueueTheServerFillsKeepsItsLineAndNamesNoMix() {
        showNowPlaying(PlayerSample.nowPlaying.copy(canShuffle = false))
        compose.onNodeWithText("from Blue Hour").assertExists()
        val state = SemanticsProperties.StateDescription
        compose.onNodeWithContentDescription("Shuffle").assert(SemanticsMatcher.keyNotDefined(state))
        compose.onNodeWithContentDescription("Repeat").assert(SemanticsMatcher.expectValue(state, "Off"))
    }
}
