package io.github.hazymorning.amsel.ui.screens.search

import androidx.lifecycle.SavedStateHandle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.ItemMapping
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.SearchResults
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val asked = mutableListOf<String>()
    private val playing = MutableStateFlow<String?>(null)

    /** What the server does for a query; by default it finds one song named after it. */
    private var server: suspend (String) -> SearchResults = { q -> SearchResults(tracks = listOf(track(q))) }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun track(name: String) = MediaItem("1", "library", name, "library://track/$name", "track")

    private fun TestScope.viewModel(handle: SavedStateHandle = SavedStateHandle()): SearchViewModel {
        val vm = SearchViewModel(
            handle,
            search = { q ->
                asked += q
                server(q)
            },
            mapper = UiMapper { null },
            playing = playing,
            background = UnconfinedTestDispatcher(testScheduler),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test fun thePlayingSongIsMarkedAndFollowsTheSpeaker() = runTest {
        val vm = viewModel()
        playing.value = "library://track/blue"
        assertEquals("library://track/blue", vm.state.value.playingKey)
        playing.value = null
        assertNull(vm.state.value.playingKey)
    }

    @Test fun theFieldUpdatesAtOnceAndTheServerIsAsked300MsLater() = runTest {
        val vm = viewModel()
        vm.onQueryChange("b")
        vm.onQueryChange("bl")
        vm.onQueryChange("blu")
        assertEquals("blu", vm.state.value.query)
        assertTrue(vm.state.value.searching)
        advanceTimeBy(299)
        assertEquals(emptyList<String>(), asked)
        advanceTimeBy(2)
        assertEquals(listOf("blu"), asked)
        val state = vm.state.value
        assertFalse(state.searching)
        assertEquals("blu", state.results?.query)
        assertEquals("blu", state.topTitle())
    }

    @Test fun onlyTheLatestQueryAnswers() = runTest {
        val first = CompletableDeferred<SearchResults>()
        server = { q -> if (q == "blue") first.await() else SearchResults(tracks = listOf(track(q))) }
        val vm = viewModel()
        vm.onQueryChange("blue")
        advanceTimeBy(301)
        vm.onQueryChange("blues")
        advanceTimeBy(301)
        // The newer query does not wait for the older one, which is dropped.
        assertEquals(listOf("blue", "blues"), asked)
        assertEquals("blues", vm.state.value.topTitle())
        first.complete(SearchResults(tracks = listOf(track("stale"))))
        advanceUntilIdle()
        assertEquals("blues", vm.state.value.results?.query)
        assertEquals("blues", vm.state.value.topTitle())
    }

    @Test fun clearingShowsNothingAndStopsTheSearch() = runTest {
        var stopped = false
        server = {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val vm = viewModel()
        vm.onQueryChange("blue")
        advanceTimeBy(301)
        assertTrue(vm.state.value.searching)
        vm.onQueryChange("")
        val state = vm.state.value
        assertEquals("", state.query)
        assertFalse(state.searching)
        assertNull(state.results)
        assertTrue(stopped)
    }

    @Test fun spacesAloneAreNotASearch() = runTest {
        val vm = viewModel()
        vm.onQueryChange("   ")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), asked)
        assertFalse(vm.state.value.searching)
    }

    @Test fun aLostConnectionSaysSoInPlaceAndRetryAsksAtOnce() = runTest {
        server = { throw MaConnectionException("Not connected") }
        val vm = viewModel()
        vm.onQueryChange("blue")
        advanceTimeBy(301)
        assertEquals(PlaybackR.string.error_not_connected, vm.state.value.failure)
        assertFalse(vm.state.value.searching)

        server = { q -> SearchResults(tracks = listOf(track(q))) }
        vm.retry()
        assertEquals(listOf("blue", "blue"), asked)
        assertNull(vm.state.value.failure)
        assertEquals("blue", vm.state.value.results?.query)
    }

    @Test fun anErrorIsAPlainSentenceInPlaceOfAnEarlierQuerysResults() = runTest {
        val vm = viewModel()
        vm.onQueryChange("red")
        advanceTimeBy(301)
        server = { throw MaCommandException("music/search", 999, "boom") }
        vm.onQueryChange("blue")
        advanceTimeBy(301)
        assertEquals(R.string.search_failed, vm.state.value.failure)
        assertNull(vm.state.value.results)
        assertFalse(vm.state.value.searching)
    }

    @Test fun theQuerySurvivesProcessDeath() = runTest {
        val handle = SavedStateHandle()
        viewModel(handle).onQueryChange("blue")

        val restored = viewModel(SavedStateHandle(mapOf("query" to handle.get<String>("query"))))
        assertEquals("blue", restored.state.value.query)
        assertTrue(restored.state.value.searching)
        advanceTimeBy(301)
        assertEquals("blue", restored.state.value.results?.query)
    }

    @Test fun albumsKnowTheirArtist() = runTest {
        val artist = ItemMapping("Kestrel", "library://artist/9")
        server =
            {
                SearchResults(
                    albums = listOf(
                        MediaItem("3", "library", "Blue", "library://album/3", "album", artists = listOf(artist)),
                    ),
                )
            }
        val vm = viewModel()
        vm.onQueryChange("blue")
        advanceTimeBy(301)
        val album = (vm.state.value.results?.top as TopResult.Item).item
        assertEquals("library://artist/9", album.artistKey)
    }

    private fun SearchUiState.topTitle() = (results?.top as? TopResult.Song)?.track?.title
}
