package io.github.hazymorning.amsel.ui.screens.home

import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.data.home.HomeItem
import io.github.hazymorning.amsel.data.home.HomeLayout
import io.github.hazymorning.amsel.data.home.HomeSource
import io.github.hazymorning.amsel.data.home.SectionItems
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeeAllViewModelTest {
    private val mapper = UiMapper { null }
    private val main = UnconfinedTestDispatcher()

    private fun item(kind: String, name: String, artist: String? = null) =
        HomeItem("library://$kind/$name", kind, name, artistUri = artist)

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() = Dispatchers.resetMain()

    private val home = object : HomeSource {
        override val layout = MutableStateFlow<HomeLayout?>(HomeLayout())
        override val items = MutableStateFlow<Map<SectionOption, SectionItems>?>(null)
        override val changes = MutableSharedFlow<SectionOption>(extraBufferCapacity = 4)

        override suspend fun edit(change: (HomeLayout) -> HomeLayout) = Unit

        override suspend fun refresh() = Unit

        override suspend fun reloadFailed() = Unit
    }

    private fun viewModel(option: SectionOption, load: suspend (SectionOption) -> SectionItems) =
        SeeAllViewModel(option, home, load, mapper::media, worker = main)

    @Test fun asksForItsOptionAndListsWhatComes() {
        val answer = CompletableDeferred<SectionItems>()
        var asked: SectionOption? = null
        val vm = viewModel(SectionOption.NewAlbums) {
            asked = it
            answer.await()
        }
        assertEquals(SectionOption.NewAlbums, asked)
        // Before the answer: the skeleton, never "Nothing here yet".
        assertTrue(vm.state.value.loading)
        answer.complete(SectionItems(listOf(item("album", "New", "library://artist/7"), item("album", "Old"))))
        val loaded = vm.state.value
        assertEquals(listOf("New", "Old"), loaded.items.map { it.title })
        assertFalse(loaded.loading)
        assertTrue(loaded.grid)

        val album = loaded.items.first()
        assertEquals("library://artist/7", album.artistKey)
        assertNull(loaded.items.last().artistKey)
    }

    @Test fun anythingButAlbumsListsAsRows() {
        val vm = viewModel(SectionOption.Everything) {
            SectionItems(listOf(item("album", "A"), item("playlist", "P")))
        }
        assertFalse(vm.state.value.grid)
    }

    @Test fun recentlyPlayedFallingBackIsTitledYourAlbums() {
        val vm = viewModel(SectionOption.Everything) { SectionItems(listOf(item("album", "A")), fallback = true) }
        assertTrue(vm.state.value.fallback)
    }

    @Test fun whileItLoadsItIsTitledAsHomeShowsIt() {
        home.items.value = mapOf(SectionOption.Everything to SectionItems(listOf(item("album", "A")), fallback = true))
        val vm = viewModel(SectionOption.Everything) { CompletableDeferred<SectionItems>().await() }
        assertTrue(vm.state.value.loading)
        assertTrue(vm.state.value.fallback)
    }

    @Test fun aPinTakenBackLeavesThePage() {
        home.layout.value = HomeLayout(pinned = listOf("library://album/A", "library://album/B"))
        var asks = 0
        val vm = viewModel(SectionOption.Pinned) {
            asks++
            SectionItems(home.layout.value!!.pinned.map { item("album", it.substringAfterLast('/')) })
        }
        assertEquals(listOf("A", "B"), vm.state.value.items.map { it.title })
        home.layout.value = home.layout.value!!.unpin("library://album/A")
        home.changes.tryEmit(SectionOption.Pinned)
        assertEquals(listOf("B"), vm.state.value.items.map { it.title })
        // Another section's load leaves this page alone.
        home.changes.tryEmit(SectionOption.NewAlbums)
        assertEquals(2, asks)
    }

    @Test fun nothingThereIsEmptyNotLoading() {
        val state = viewModel(SectionOption.FavoriteStations) { SectionItems() }.state.value
        assertFalse(state.loading)
        assertNull(state.failure)
        assertTrue(state.items.isEmpty())
    }

    @Test fun aFailedAskOffersRetry() {
        var fail = true
        val vm = viewModel(SectionOption.RecentAlbums) {
            if (fail) throw MaConnectionException("offline")
            SectionItems(listOf(item("album", "A")))
        }
        assertEquals(PlaybackR.string.error_not_connected, vm.state.value.failure)
        fail = false
        vm.retry()
        assertNull(vm.state.value.failure)
        assertEquals(1, vm.state.value.items.size)
    }

    @Test fun aRefusalWithNothingShownSaysSoInsteadOfEmpty() {
        val state = viewModel(SectionOption.RecentAlbums) {
            throw MaCommandException(
                "music/albums/library_items",
                MaCommandException.ERROR_INSUFFICIENT_PERMISSIONS,
                "no",
            )
        }.state.value
        assertEquals(PlaybackR.string.error_not_allowed, state.failure)
    }
}
