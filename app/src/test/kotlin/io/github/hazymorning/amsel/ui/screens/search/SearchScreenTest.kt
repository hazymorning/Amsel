package io.github.hazymorning.amsel.ui.screens.search

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.hazymorning.amsel.sample.SearchSampleData
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
class SearchScreenTest {
    @get:Rule val compose = createComposeRule()

    private val results = SearchUiState(
        query = SearchSampleData.QUERY,
        results = SearchResultsUi(SearchSampleData.QUERY, songs = SearchSampleData.songs),
    )

    private fun show(state: SearchUiState, onQueryChange: (String) -> Unit = {}, onPlayTrack: (TrackUi) -> Unit = {}) =
        compose.setContent {
            AmselTheme { SearchScreen(state, onQueryChange, onPlayTrack, {}, {}, {}, {}) }
        }

    @Test fun typingReportsTheQuery() {
        var typed = ""
        show(SearchUiState(), onQueryChange = { typed = it })
        compose.onNode(hasSetTextAction()).performTextInput("blue")
        assertEquals("blue", typed)
    }

    @Test fun clearEmptiesTheField() {
        var typed: String? = null
        show(results, onQueryChange = { typed = it })
        compose.onNodeWithContentDescription("Clear search").performClick()
        assertEquals("", typed)
    }

    @Test fun tappingASongPlaysIt() {
        var played: TrackUi? = null
        show(results, onPlayTrack = { played = it })
        compose.onNodeWithText("Into the Blue").performClick()
        assertEquals(SearchSampleData.songs[1], played)
    }

    @Test fun aPlayingStationIsMarkedLikeASong() {
        val station = SearchSampleData.radio.first()
        show(
            SearchUiState(
                query = SearchSampleData.QUERY,
                results = SearchResultsUi(SearchSampleData.QUERY, radio = SearchSampleData.radio),
                playingKey = station.key,
            ),
        )
        compose.onNode(hasText(station.title) and hasStateDescription("Now playing")).assertExists()
        compose.onNode(hasText(SearchSampleData.radio[1].title) and hasStateDescription("Now playing"))
            .assertDoesNotExist()
    }

    @Test fun nothingFoundNamesTheQuery() {
        show(SearchUiState(query = "zzyzx", results = SearchResultsUi("zzyzx")))
        compose.onNodeWithText("Nothing found for “zzyzx”").assertExists()
    }
}
