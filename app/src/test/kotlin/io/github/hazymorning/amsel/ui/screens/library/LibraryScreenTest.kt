package io.github.hazymorning.amsel.ui.screens.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.api.LibrarySort
import io.github.hazymorning.amsel.sample.LibrarySampleData
import io.github.hazymorning.amsel.sample.SampleData
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Touch and TalkBack paths through the Library screen. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LibraryScreenTest {
    @get:Rule val compose = createComposeRule()

    private val artists = LibrarySampleData.artistsAToZ
    private val aToZ = LibraryUiState(
        LibraryKind.Artists,
        LibrarySort.AToZ,
        artists,
        letters = letterJumps(artists.map { LibrarySampleData.sortName(it.title) }),
    )

    private fun show(
        state: LibraryUiState,
        onSelectKind: (LibraryKind) -> Unit = {},
        onSort: (LibrarySort) -> Unit = {},
        onRefresh: () -> Unit = {},
        onMore: (MediaUi) -> Unit = {},
    ) = compose.setContent {
        AmselTheme { LibraryScreen(state, onSelectKind, onSort, {}, onMore, {}, {}, onRefresh = onRefresh) }
    }

    private fun SemanticsNodeInteraction.refresh() {
        val actions = fetchSemanticsNode().config[SemanticsActions.CustomActions]
        actions.single { it.label == "Refresh" }.action()
    }

    @Test fun chipSelectsKind() {
        var picked: LibraryKind? = null
        show(aToZ, onSelectKind = { picked = it })
        compose.onNodeWithText("Albums").performClick()
        assertEquals(LibraryKind.Albums, picked)
    }

    @Test fun aSidewaysSwipeGoesToTheChipBesideAndBack() {
        val picked = mutableListOf<LibraryKind>()
        val lists = mapOf(LibraryKind.Albums to SampleData.albums, LibraryKind.Artists to artists)
        var kind by mutableStateOf(LibraryKind.Albums)
        compose.setContent {
            AmselTheme {
                LibraryScreen(
                    LibraryUiState(kind, items = lists.getValue(kind)),
                    onSelectKind = {
                        picked += it
                        kind = it
                    },
                    onSort = {},
                    onOpen = {},
                    onMore = {},
                    onOpenWebUi = {},
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText(SampleData.albums.first().title)
            .performTouchInput { swipeLeft(startX = centerX + 300f, endX = centerX - 300f) }
        compose.onNodeWithText(artists.first().title)
            .performTouchInput { swipeRight(startX = centerX - 300f, endX = centerX + 300f) }
        assertEquals(listOf(LibraryKind.Artists, LibraryKind.Albums), picked)
    }

    @Test fun aSwipePastTheLastChipGoesRoundToTheFirst() {
        val picked = mutableListOf<LibraryKind>()
        show(aToZ, onSelectKind = { picked += it })
        val row = compose.onNodeWithText(artists.first().title)
        row.performTouchInput { swipeLeft(startX = centerX + 300f, endX = centerX - 300f) }
        assertEquals(listOf(LibraryKind.Playlists), picked)
    }

    @Test fun aSwipeBackFromTheFirstChipGoesRoundToTheLast() {
        val picked = mutableListOf<LibraryKind>()
        show(LibraryUiState(LibraryKind.Playlists, items = LibrarySampleData.playlists), onSelectKind = {
            picked += it
        })
        val row = compose.onNodeWithText(LibrarySampleData.playlists.first().title)
        row.performTouchInput { swipeRight(startX = centerX - 300f, endX = centerX + 300f) }
        assertEquals(listOf(LibraryKind.Artists), picked)
    }

    @Test fun aShortSwipeGoesNowhere() {
        val picked = mutableListOf<LibraryKind>()
        show(aToZ, onSelectKind = { picked += it })
        val row = compose.onNodeWithText(artists.first().title)
        row.performTouchInput { swipeRight(startX = centerX, endX = centerX + 60f) }
        assertEquals(emptyList<LibraryKind>(), picked)
    }

    @Test fun rowsThatStandTogetherStandAlike() {
        val named = MediaUi("library://playlist/1", MediaKind.Playlist, "Dinner", subtitle = "Anna")
        val plain = MediaUi("library://playlist/2", MediaKind.Playlist, "Road trip")
        show(LibraryUiState(LibraryKind.Playlists, items = listOf(named, plain)))
        // One playlist names its owner, so the other says what it is.
        compose.onNodeWithText("Anna").assertExists()
        compose.onNodeWithText("Playlist").assertExists()
    }

    @Test fun aListWithoutSecondLinesKeepsToOne() {
        show(aToZ)
        compose.onNodeWithText("Artist").assertDoesNotExist()
    }

    @Test fun theOrderInWordsMovesToTheNext() {
        var picked: LibrarySort? = null
        show(LibraryUiState(LibraryKind.Playlists, items = LibrarySampleData.playlists), onSort = { picked = it })
        compose.onNodeWithContentDescription("Sorted by Recently played. Change order").performClick()
        assertEquals(LibrarySort.RecentlyAdded, picked)
    }

    @Test fun scrubberIsOneAdjustableControlForTalkBack() {
        show(aToZ)
        val m = aToZ.letters.indexOfFirst { it.letter == "M" }
        compose.onNodeWithContentDescription("Jump to letter")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(m.toFloat()) }
        compose.waitForIdle()
        compose.onNodeWithText("Marlene Vogt").assertIsDisplayed()
    }

    /** The thumb lingers after a scroll; only it takes a touch, so a long press on a row still opens its actions. */
    @Test fun aRowsActionsStayInReachWhileTheThumbShows() {
        var more: MediaUi? = null
        compose.mainClock.autoAdvance = false
        show(aToZ, onMore = { more = it })
        compose.mainClock.advanceTimeBy(FRAME_MS)
        compose.onNode(hasScrollToIndexAction()).performTouchInput {
            down(center)
            moveBy(Offset(0f, -viewConfiguration.touchSlop - 40.dp.toPx()))
            advanceEventTime(HOLD_MS)
            up()
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        val row = artists[5]
        compose.onNodeWithText(row.title).performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        assertEquals(row, more)
        compose.onNodeWithText(artists[0].title).assertIsDisplayed()
    }

    @Test fun talkBackRefreshesFromAnyRow() {
        var refreshed = 0
        show(LibraryUiState(LibraryKind.Playlists, items = LibrarySampleData.playlists), onRefresh = { refreshed++ })
        compose.onNodeWithText(LibrarySampleData.playlists[1].title).refresh()
        assertEquals(1, refreshed)
    }

    @Test fun anEmptyListCanBeRefreshed() {
        var refreshed = 0
        show(LibraryUiState(LibraryKind.Albums), onRefresh = { refreshed++ })
        compose.onNodeWithText("No albums yet").refresh()
        assertEquals(1, refreshed)
    }

    @Test fun shortListsHaveNoScrubber() {
        show(LibraryUiState(LibraryKind.Playlists, items = LibrarySampleData.playlists))
        compose.onNodeWithContentDescription("Jump to letter").assertDoesNotExist()
    }
}

private const val FRAME_MS = 16L
private const val HOLD_MS = 100L
private const val SETTLE_MS = 300L
