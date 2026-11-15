package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.sample.PlayerSample
import io.github.hazymorning.amsel.sample.SpokenSample
import io.github.hazymorning.amsel.ui.model.ActionsContext
import io.github.hazymorning.amsel.ui.model.ChapterUi
import io.github.hazymorning.amsel.ui.model.ItemAction
import io.github.hazymorning.amsel.ui.model.ItemActionsUiState
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.PlayerControl
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.CoverColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PlayerScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        AmselTheme { content() }
    }

    private fun customAction(text: String, label: String) {
        val actions = compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == label }.action() }
    }

    private val fixed = CoverColors(hue = 205f, saturation = 0.3f)

    @Test fun transportSendsControls() {
        val sent = mutableListOf<PlayerControl>()
        var closed = false
        show {
            NowPlayingScreen(
                PlayerSample.nowPlaying,
                onControl = { sent += it },
                onClose = { closed = true },
                onMore = {},
                onArtist = {},
                onSpeaker = {},
                onUpNext = {},
                colors = fixed,
            )
        }
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithContentDescription("Shuffle").performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals(
            listOf(PlayerControl.PlayPause, PlayerControl.Next, PlayerControl.SetShuffle(false)),
            sent,
        )
        assertEquals(true, closed)
    }

    @Test fun aBookSkipsBySecondsAndStepsByChapter() {
        val sent = mutableListOf<PlayerControl>()
        var chapters = false
        show {
            NowPlayingScreen(
                PlayerSample.book,
                onControl = { sent += it },
                onClose = {},
                onMore = {},
                onArtist = {},
                onSpeaker = {},
                onUpNext = {},
                colors = fixed,
                onChapters = { chapters = true },
            )
        }
        compose.onNodeWithText("Chapter 3 · The Spouter-Inn").assertExists()
        compose.onNodeWithContentDescription("Back 15 seconds").performClick()
        compose.onNodeWithContentDescription("Next chapter").performClick()
        compose.onNodeWithContentDescription("Forward 30 seconds").performClick()
        compose.onNodeWithContentDescription("Previous chapter").performClick()
        compose.onNodeWithContentDescription("Chapters").performClick()
        assertEquals(
            listOf(PlayerControl.SeekBack, PlayerControl.Next, PlayerControl.SeekForward, PlayerControl.Previous),
            sent,
        )
        assertEquals(true, chapters)
        compose.onNodeWithContentDescription("Position")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Chapter 3 of 16, 4:14 of 22:18"),
            )
    }

    @Test fun seekBarIsASliderForTalkBack() {
        val sent = mutableListOf<PlayerControl>()
        show {
            NowPlayingScreen(PlayerSample.nowPlaying, { sent += it }, {}, {}, {}, {}, {}, colors = fixed)
        }
        compose.onNodeWithContentDescription("Position")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1:11 of 3:34"))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        assertEquals(listOf(PlayerControl.Seek(107_000)), sent)
    }

    @Test fun aSwipeOrALongPressOnTheLineLeavesTheBookWhereItIs() {
        val sent = mutableListOf<PlayerControl>()
        show { NowPlayingScreen(PlayerSample.book, { sent += it }, {}, {}, {}, {}, {}, colors = fixed) }
        val line = compose.onNodeWithContentDescription("Position")
        // Swiping Now Playing closed across the line, as in the household's test.
        line.performTouchInput {
            down(Offset(width * 0.1f, centerY))
            repeat(10) { moveBy(Offset(if (it % 2 == 0) 1f else -1f, 40f)) }
            up()
        }
        line.performTouchInput {
            down(Offset(width * 0.05f, centerY))
            advanceEventTime(800)
            up()
        }
        assertEquals(emptyList<PlayerControl>(), sent)
    }

    @Test fun aSidewaysSwipeOnTheCoverIsNextAndPrevious() {
        val sent = mutableListOf<PlayerControl>()
        show { NowPlayingScreen(PlayerSample.nowPlaying, { sent += it }, {}, {}, {}, {}, {}, colors = fixed) }
        val cover = Offset(0.5f, 0.3f)
        compose.onRoot().performTouchInput {
            swipe(Offset(width * 0.75f, height * cover.y), Offset(width * 0.25f, height * cover.y), 200)
        }
        compose.onRoot().performTouchInput {
            swipe(Offset(width * 0.25f, height * cover.y), Offset(width * 0.75f, height * cover.y), 200)
        }
        // Too short to mean it.
        compose.onRoot().performTouchInput {
            swipe(Offset(width * 0.5f, height * cover.y), Offset(width * 0.55f, height * cover.y), 200)
        }
        assertEquals(listOf(PlayerControl.Next, PlayerControl.Previous), sent)
    }

    @Test fun aTapOrASidewaysDragOnTheLineMovesInTheChapter() {
        val sent = mutableListOf<PlayerControl>()
        show { NowPlayingScreen(PlayerSample.book, { sent += it }, {}, {}, {}, {}, {}, colors = fixed) }
        val chapter = SpokenSample.chapters[2]
        val line = compose.onNodeWithContentDescription("Position")
        line.performTouchInput { click(Offset(width * 0.5f, centerY)) }
        line.performTouchInput { swipe(Offset(width * 0.2f, centerY), Offset(width * 0.8f, centerY), 300) }
        assertEquals(2, sent.size)
        val (tap, drag) = sent.map { (it as PlayerControl.Seek).positionMs }
        val length = chapter.endMs - chapter.startMs
        assertEquals(chapter.startMs + length / 2.0, tap.toDouble(), length * 0.05)
        assertEquals(chapter.startMs + length * 0.8, drag.toDouble(), length * 0.05)
    }

    @Test fun fixedVolumeHasNoVolumeButton() {
        show { NowPlayingScreen(PlayerSample.pausedLongTitle, {}, {}, {}, {}, {}, {}, colors = fixed) }
        compose.onNodeWithContentDescription("Volume").assertDoesNotExist()
        compose.onNodeWithContentDescription("Play").assertExists()
        compose.onNodeWithContentDescription("Previous").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next").assertIsNotEnabled()
    }

    @Test fun aRunningSleepTimerShowsBesideTheMenuAndOpensItsSheet() {
        var opened = 0
        show {
            NowPlayingScreen(
                PlayerSample.book,
                onControl = {},
                onClose = {},
                onMore = {},
                onArtist = {},
                onSpeaker = {},
                onUpNext = {},
                colors = fixed,
                sleepLeft = "44:12",
                onSleep = { opened++ },
            )
        }
        val moon = compose.onNodeWithContentDescription("Sleep timer, 44:12 left")
        val menu = compose.onNodeWithContentDescription("More options for Moby-Dick")
        moon.performClick()
        assertEquals(1, opened)
        val moonBox = moon.fetchSemanticsNode().boundsInRoot
        val menuBox = menu.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the moon stands in the top bar, before the menu",
            moonBox.center.y == menuBox.center.y && moonBox.right <= menuBox.left + 1f,
        )
    }

    @Test fun theFootersGroundsStandOnTheArtworksMarginsAndAsFarFromTheFootAsFromTheSides() {
        show {
            NowPlayingScreen(
                PlayerSample.nowPlaying,
                onControl = {},
                onClose = {},
                onMore = {},
                onArtist = {},
                onSpeaker = {},
                onUpNext = {},
                colors = fixed,
            )
        }
        val density = compose.density.density
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val reach = 2 * density
        val pill = compose.onNodeWithContentDescription(
            "Speaker: Kitchen, playing. Choose speaker",
        ).fetchSemanticsNode().boundsInRoot
        val upNext = compose.onNodeWithContentDescription("Up next").fetchSemanticsNode().boundsInRoot
        val margin = 24 * density
        // The grounds are 44 dp in 48 dp targets: the targets reach 2 dp past them, down and, for a disc, sideways.
        assertEquals(margin, pill.left, 1f)
        assertEquals(margin, screen.right - (upNext.right - reach), 1f)
        assertEquals(margin, screen.bottom - (upNext.bottom - reach), 1f)
        assertEquals(margin, screen.bottom - (pill.bottom - reach), 1f)
    }

    @Test fun upNextTapPlaysAndTalkBackMovesAndRemoves() {
        val played = mutableListOf<String>()
        val moves = mutableListOf<Pair<String, Int>>()
        val removed = mutableListOf<String>()
        show {
            UpNextScreen(
                UpNextUiState(PlayerSample.upNext),
                onClose = {},
                onPlay = { played += it },
                onMove = { id, to -> moves += id to to },
                onRemove = { removed += it },
                onMore = {},
                onRetry = {},
            )
        }
        compose.onNodeWithText("Slow Tide").performClick()
        customAction("The Quiet Part", "Move down")
        customAction("Northbound", "Remove from Up next")
        compose.onNodeWithText("Lanterns")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Now playing"))
        assertEquals(listOf("q4"), played)
        assertEquals(listOf("q2" to 3), moves)
        assertEquals(listOf("q5"), removed)
        compose.onNodeWithText("Northbound").assertDoesNotExist()
    }

    @Test fun aRowARefusedRemovalBringsBackDoesNotRemoveItselfAgain() {
        val removed = mutableListOf<String>()
        var state by mutableStateOf(UpNextUiState(PlayerSample.upNext))
        show {
            UpNextScreen(
                state,
                onClose = {},
                onPlay = {},
                onMove = { _, _ -> },
                onRemove = { removed += it },
                onMore = {},
                onRetry = {},
            )
        }
        compose.onNodeWithText("Northbound").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        state = UpNextUiState(PlayerSample.upNext.filterNot { it.id == "q5" })
        compose.waitForIdle()
        // The server refused: Up next reads the queue again, the song still in it.
        state = UpNextUiState(PlayerSample.upNext.toList())
        compose.waitForIdle()
        assertEquals(listOf("q5"), removed)
        compose.onNodeWithText("Northbound").assertExists()
    }

    @Test fun draggingTheHandleMovesTheSong() {
        val moves = mutableListOf<Pair<String, Int>>()
        show {
            UpNextScreen(
                UpNextUiState(PlayerSample.upNext),
                onClose = {},
                onPlay = {},
                onMove = { id, to -> moves += id to to },
                onRemove = {},
                onMore = {},
                onRetry = {},
            )
        }
        val row = compose.onNodeWithText("The Quiet Part")
        row.performTouchInput { down(Offset(width - 32.dp.toPx(), centerY)) }
        repeat(14) {
            row.performTouchInput { moveBy(Offset(0f, 10.dp.toPx())) }
            compose.waitForIdle()
        }
        row.performTouchInput { up() }
        compose.waitForIdle()
        // Two rows down: from index 2 to index 4.
        assertEquals(listOf("q2" to 4), moves)
    }

    @Test fun playOnSelectsAvailableSpeakersOnly() {
        val selected = mutableListOf<String>()
        show {
            PlayOnSheetContent(
                PlayOnUiState(PlayerSample.speakers, currentId = "phone"),
                onSelect = { selected += it.id },
            )
        }
        compose.onNodeWithText("Kitchen").performClick()
        compose.onNodeWithText("Garden").assertDoesNotExist()
        compose.onNodeWithText("This phone").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        assertEquals(listOf("kitchen"), selected)
    }

    @Test fun aSpeakerThatPlaysSaysSoAndOneThatRestsSaysPaused() {
        val speakers = PlayerSample.speakers + SpeakerUi("tv", "Television", isPlaying = true)
        show { PlayOnSheetContent(PlayOnUiState(speakers, "phone"), onSelect = {}) }
        val playing = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Now playing")
        compose.onNode(hasText("Lanterns · Marlene Vogt") and playing).assertIsDisplayed()
        // It plays something that has no name here, an input of its own: still it plays.
        compose.onNode(hasText("Playing") and playing).assertExists()
        compose.onNodeWithText("Paused · Cooking · Playlist").assertExists()
        compose.onAllNodes(playing).assertCountEquals(2)
    }

    @Test fun itemActionsReportTheTappedAction() {
        val tapped = mutableListOf<ItemAction>()
        show {
            ItemActionsSheetContent(
                ItemActionsUiState.forTrack(PlayerSample.upNext[2].track, ActionsContext.UpNext),
                onAction = { tapped += it },
            )
        }
        compose.onNodeWithText("Remove from Up next").performClick()
        compose.onNodeWithText("Play next").performClick()
        assertEquals(listOf(ItemAction.RemoveFromUpNext, ItemAction.PlayNext), tapped)
    }

    @Test fun removingFromAPlaylistAsksInTheSheet() {
        val answers = mutableListOf<String>()
        show {
            RemoveFromPlaylistQuestion(
                "The Quiet Part",
                playlist = "Sunday Morning",
                onRemove = { answers += "remove" },
                onBack = { answers += "back" },
            )
        }
        compose.onNodeWithText("Remove “The Quiet Part” from Sunday Morning?").assertExists()
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("remove", "back"), answers)
    }

    private fun showNowPlaying(state: NowPlayingUi, opened: MutableList<String>) = show {
        NowPlayingScreen(
            state,
            onControl = {},
            onClose = {},
            onMore = {},
            onArtist = {},
            onSpeaker = {},
            onUpNext = { opened += "up next" },
            colors = fixed,
            onChapters = { opened += "chapters" },
        )
    }

    @Test fun aBooksChaptersOpenOnlyFromTheButtonAtTheBottomRight() {
        val opened = mutableListOf<String>()
        showNowPlaying(PlayerSample.book, opened)
        // Named under the title, never listed on the page, where a stray touch went to another chapter.
        compose.onNodeWithText("Chapter 3 · The Spouter-Inn").assertExists()
        compose.onNodeWithText("The Spouter-Inn").assertDoesNotExist()
        compose.onNodeWithText("The Counterpane").assertDoesNotExist()
        compose.onNodeWithContentDescription("Chapters").performClick()
        assertEquals(listOf("chapters"), opened)
    }

    @Test fun anEpisodeWithoutChaptersStillHasTheChaptersButton() {
        val opened = mutableListOf<String>()
        showNowPlaying(PlayerSample.book.copy(kind = MediaKind.Episode, chapters = emptyList()), opened)
        compose.onNodeWithContentDescription("Up next").assertDoesNotExist()
        compose.onNodeWithContentDescription("Chapters").performClick()
        assertEquals(listOf("chapters"), opened)
    }

    @Test fun musicHasUpNextWhereABookHasChapters() {
        val opened = mutableListOf<String>()
        showNowPlaying(PlayerSample.nowPlaying, opened)
        compose.onNodeWithContentDescription("Chapters").assertDoesNotExist()
        compose.onNodeWithContentDescription("Up next").performClick()
        assertEquals(listOf("up next"), opened)
    }

    @Test fun theChaptersSheetOpensOnThePlayingChapterAndATapGoesToOne() {
        val picked = mutableListOf<ChapterUi>()
        show { ChaptersSheetContent(PlayerSample.book, onPick = { picked += it }) }
        compose.onNodeWithText(
            "The Spouter-Inn",
        ).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithText("The Spouter-Inn").performClick()
        assertEquals(listOf(SpokenSample.chapters[2]), picked)
    }

    @Test fun theChaptersSheetSaysWhenThereAreNone() {
        show { ChaptersSheetContent(PlayerSample.book.copy(chapters = emptyList()), onPick = {}) }
        compose.onNodeWithText("This book has no chapters.").assertExists()
    }
}
