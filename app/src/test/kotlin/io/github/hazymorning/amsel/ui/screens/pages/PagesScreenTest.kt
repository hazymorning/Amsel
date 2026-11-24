package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.sample.PagesSample
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PagesScreenTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<MediaUi>()
    private val more = mutableListOf<MediaUi>()
    private val played = mutableListOf<List<String>>()
    private val moreSongs = mutableListOf<TrackUi>()
    private var retries = 0

    // The page's own vertical list, not the shelf inside it.
    private val page = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun artist(state: ArtistUiState) = compose.setContent {
        AmselTheme {
            ArtistScreen(
                state,
                onBack = {},
                onPlay = {},
                onShuffle = {},
                onPlayTrack = {},
                onMore = { more += it },
                onMoreTrack = {},
                onOpen = { opened += it },
                onRetry = {},
            )
        }
    }

    private fun similarSongs(state: SimilarSongsUiState) = compose.setContent {
        AmselTheme {
            SimilarSongsScreen(
                state,
                title = "Lanterns",
                onBack = {},
                onPlayTrack = { played += state.from(it) },
                onMoreTrack = { moreSongs += it },
                onRetry = { retries++ },
            )
        }
    }

    @Test fun aSimilarArtistOpensWithATapAndOffersItsActionsOnALongPress() {
        val state = PagesSample.artist
        artist(state)
        compose.onNode(page).performScrollToNode(hasText(state.similar[0].title))
        compose.onNodeWithText("Similar artists").assertExists()
        compose.onNodeWithText(state.similar[0].title).performClick()
        compose.onNodeWithText(state.similar[1].title).performTouchInput { longClick() }
        assertEquals(listOf(state.similar[0]), opened)
        assertEquals(listOf(state.similar[1]), more)
    }

    @Test fun anArtistWithoutSimilarArtistsHasNoSuchHeading() {
        artist(PagesSample.artist.copy(similar = emptyList()))
        compose.onNode(page).performScrollToNode(hasText(PagesSample.artist.albums.last().title))
        compose.onAllNodesWithText("Similar artists").assertCountEquals(0)
    }

    @Test fun aTapOnASimilarSongPlaysFromThereOnAndALongPressOffersItsActions() {
        val state = PagesSample.similarSongs
        similarSongs(state)
        compose.onNodeWithText("Similar to “Lanterns”").assertExists()
        compose.onNodeWithText(state.songs[2].title).performClick()
        compose.onNodeWithText(state.songs[4].title).performTouchInput { longClick() }
        assertEquals(listOf(state.songs.drop(2).map { it.key }), played)
        assertEquals(listOf(state.songs[4]), moreSongs)
    }

    @Test fun nothingSimilarIsSaidPlainlyWithNothingToRetry() {
        similarSongs(SimilarSongsUiState())
        compose.onNodeWithText("Nothing similar found").assertExists()
        compose.onAllNodesWithText("Retry").assertCountEquals(0)
    }

    @Test fun aLostConnectionOffersRetry() {
        similarSongs(SimilarSongsUiState(failure = PlaybackR.string.error_not_connected))
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
        compose.onAllNodesWithText("Nothing similar found").assertCountEquals(0)
    }
}
