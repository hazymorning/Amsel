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
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
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

    private var server: suspend (String) -> SearchResults = { q -> SearchResults(tracks = listOf(track(q))) }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    /** Collects the last view model's state as the screen does; cancel it to leave Search. */
    private lateinit var screen: Job

    private fun track(name: String) = MediaItem("1", "library", name, "library://track/$name", "track")

    private fun TestScope.viewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        library: Flow<List<MediaUi>> = flowOf(emptyList()),
    ): SearchViewModel {
        val vm = SearchViewModel(
            handle,
            search = { q ->
                asked += q
                server(q)
            },
            mapper = UiMapper { null },
            playing = playing,
            library = library,
            background = UnconfinedTestDispatcher(testScheduler),
        )
        show(vm)
        return vm
    }

    private fun TestScope.show(vm: SearchViewModel) {
        screen = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    }

    private fun book(progress: Float) =
        MediaUi("library://audiobook/2", MediaKind.Audiobook, "The Lantern Road", progress = progress)

    private fun album(id: Int, title: String) = MediaUi("library://album/$id", MediaKind.Album, title)

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
        // "blue" has no answer yet, and "blues" does not wait for it.
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

    @Test fun searchLeftBehindReadsNothingWhileABookPlaysOn() = runTest {
        var reads = 0
        val library = MutableStateFlow(listOf(book(0.1f)))
        viewModel(library = library.onEach { reads++ })
        assertEquals(1, reads)

        screen.cancel()
        advanceTimeBy(AWAY_MS)
        // A playing book's progress, which rewrites the index every 30 s.
        listOf(0.2f, 0.3f, 0.4f).forEach { library.value = listOf(book(it)) }
        assertEquals(1, reads)
    }

    @Test fun aSearchAfterComingBackFindsNearMatchesInTheLibraryAsItIsThen() = runTest {
        server = { SearchResults() }
        val library = MutableStateFlow(listOf(album(1, "Blackbird Song")))
        // The index read outlasts the typing, so the search has to wait for it.
        val vm = viewModel(
            library = flow {
                delay(READ_MS)
                emitAll(library)
            },
        )
        advanceTimeBy(READ_MS + 1)
        screen.cancel()
        advanceTimeBy(AWAY_MS)
        library.value = listOf(album(1, "Blackbird Song"), album(2, "Motörhead Live"))

        show(vm)
        vm.onQueryChange("motorhed")
        advanceTimeBy(READ_MS + 1)
        assertEquals("Motörhead Live", (vm.state.value.results?.top as TopResult.Item).item.title)
    }

    @Test fun anArtistTheServerSendsWithoutAPictureWearsTheCoversTheIndexMadeIt() = runTest {
        val covers = ArtRef("c1", quarters = listOf("c1", "c2", "c3", "c4").map(::ArtRef))
        val aurora = MediaUi("library://artist/3", MediaKind.Artist, "Aurora Tone", art = covers)
        server = { SearchResults(artists = listOf(MediaItem("3", "library", "Aurora Tone", aurora.key, "artist"))) }
        val vm = viewModel(library = flowOf(listOf(aurora, album(1, "Aurora Tone Live"))))
        vm.onQueryChange("aurora tone")
        advanceTimeBy(301)
        assertEquals(covers, (vm.state.value.results?.top as TopResult.Item).item.art)
    }

    private fun SearchUiState.topTitle() = (results?.top as? TopResult.Song)?.track?.title

    private companion object {
        /** Past the index's 5 s WhileSubscribed timeout. */
        const val AWAY_MS = 60_000L
        const val READ_MS = 1_000L
    }
}
