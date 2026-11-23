package io.github.hazymorning.amsel.ui.screens.search

import androidx.compose.runtime.Composable
import io.github.hazymorning.amsel.sample.SearchSampleData
import io.github.hazymorning.amsel.testing.ScreenshotTest
import io.github.hazymorning.amsel.testing.ShotVariant
import org.junit.Test

class SearchShots : ScreenshotTest() {
    private val results = SearchResultsUi(
        query = SearchSampleData.QUERY,
        top = TopResult.Item(SearchSampleData.topAlbum),
        songs = SearchSampleData.songs,
        albums = SearchSampleData.albums,
        artists = SearchSampleData.artists,
        playlists = SearchSampleData.playlists,
        radio = SearchSampleData.radio,
    )

    /** What plays (Lanterns) is not among the results, so nothing here carries the ring. */
    private val found = SearchUiState(query = SearchSampleData.QUERY, results = results)

    @Test fun results() = snap("search_results", listOf(ShotVariant.Light, ShotVariant.LargeFont)) { Search(found) }

    /** Nothing matched: one glyph and a sentence where the results would be. */
    @Test fun nothingFound() = snap("search_nothing") {
        Search(SearchUiState(query = "zzz", results = SearchResultsUi(query = "zzz")))
    }

    @Composable
    private fun Search(state: SearchUiState) = SearchScreen(state, {}, {}, {}, {}, {}, {})
}
