package io.github.hazymorning.amsel.ui.screens.library

import androidx.lifecycle.SavedStateHandle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.ImageUrls
import io.github.hazymorning.amsel.api.LibrarySort
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.ServerAddress
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.app.MessageController
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.data.library.LibraryItemEntity
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.ServerStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class LibraryViewModelTest {
    private val address = MutableStateFlow<ServerAddress?>(ServerAddress.fromBaseUrl("http://192.168.1.20:8095"))
    private val mapper = UiMapper { address.value?.let(::ImageUrls) }
    private val sources = mutableMapOf<Pair<MediaType, LibrarySort>, Flow<List<LibraryItemEntity>>>()
    private val asked = mutableListOf<Pair<MediaType, LibrarySort>>()
    private val syncing = MutableStateFlow(false)
    private val failures = MutableStateFlow<Map<MediaType, Exception>>(emptyMap())
    private val session = MutableStateFlow<SessionState>(SessionState.Ready(ServerInfo("id", "2.11.0", 99), null))
    private val resyncs = mutableListOf<MediaType>()
    private var sync = CompletableDeferred<Unit>()
    private val messages = MessageController(MutableStateFlow(true))

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun entity(
        n: Int,
        kind: String = "playlist",
        name: String = "Item $n",
        artist: String? = null,
        image: String? = null,
    ) = LibraryItemEntity(
        uri = "library://$kind/$n",
        mediaType = kind,
        name = name,
        sortName = name.lowercase(),
        subtitle = null,
        imageProxyId = image,
        year = null,
        favorite = false,
        addedRank = n,
        playedRank = n,
        isEditable = false,
        artistUri = artist,
    )

    private fun TestScope.viewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        held: Flow<Set<MediaType>> = MutableStateFlow(emptySet()),
        downloads: Flow<List<MediaUi>> = MutableStateFlow(emptyList()),
        arranged: Flow<List<LibraryKind>> = MutableStateFlow(LibraryKind.Default.visible),
        only: LibraryKind? = null,
    ): LibraryViewModel {
        val vm = LibraryViewModel(
            handle,
            observe = { type, sort ->
                asked += type to sort
                sources[type to sort] ?: MutableStateFlow(emptyList())
            },
            syncing = syncing,
            synced = MutableStateFlow(emptySet()),
            failures = failures,
            session = session,
            user = MutableStateFlow(null),
            mapper = mapper,
            resync = { type ->
                resyncs += type
                syncing.value = true
                try {
                    sync.await()
                } finally {
                    syncing.value = false
                }
            },
            messages = messages,
            work = Dispatchers.Main,
            held = held,
            arranged = arranged,
            downloads = downloads,
            only = only,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm
    }

    @Test fun startsOnRecentlyPlayedPlaylists() = runTest {
        sources[MediaType.Playlist to LibrarySort.RecentlyPlayed] = MutableStateFlow(listOf(entity(1), entity(2)))
        val state = viewModel().state.value
        assertEquals(LibraryKind.Playlists, state.kind)
        assertEquals(LibrarySort.RecentlyPlayed, state.sort)
        assertEquals(listOf("Item 1", "Item 2"), state.items.map { it.title })
        assertEquals(MediaKind.Playlist, state.items.first().kind)
        assertFalse(state.loading)
    }

    @Test fun stationsBooksAndPodcastsShowOnlyWhileTheLibraryHoldsThem() = runTest {
        val held = MutableStateFlow(emptySet<MediaType>())
        sources[MediaType.Audiobook to LibrarySort.RecentlyPlayed] =
            MutableStateFlow(listOf(entity(1, kind = "audiobook", name = "Moby-Dick")))
        val vm = viewModel(SavedStateHandle(mapOf("kind" to LibraryKind.Audiobooks.name)), held)
        assertEquals(listOf(LibraryKind.Playlists, LibraryKind.Albums, LibraryKind.Artists), vm.state.value.kinds)
        assertEquals(LibraryKind.Playlists, vm.state.value.kind)
        held.value = setOf(MediaType.Audiobook)
        assertEquals(LibraryKind.Audiobooks, vm.state.value.kinds?.last())
        assertEquals(LibraryKind.Audiobooks, vm.state.value.kind)
        assertEquals(MediaKind.Audiobook, vm.state.value.items.single().kind)
        held.value = setOf(MediaType.Audiobook, MediaType.Podcast, MediaType.Radio)
        assertEquals(LibraryKind.entries - LibraryKind.Downloads, vm.state.value.kinds)
    }

    @Test fun stationsAreAKindOfTheirOwn() = runTest {
        sources[MediaType.Radio to LibrarySort.RecentlyPlayed] =
            MutableStateFlow(listOf(entity(13, kind = "radio", name = "Blue Note Radio")))
        val vm = viewModel(held = MutableStateFlow(setOf(MediaType.Radio)))
        assertEquals(LibraryKind.Radio, vm.state.value.kinds?.last())
        vm.selectKind(LibraryKind.Radio)
        assertEquals(MediaKind.Radio, vm.state.value.items.single().kind)
    }

    @Test fun theChipsAreThoseThePersonShowsInTheirOrder() = runTest {
        val arranged = MutableStateFlow(listOf(LibraryKind.Artists, LibraryKind.Albums))
        val vm = viewModel(SavedStateHandle(mapOf("kind" to LibraryKind.Playlists.name)), arranged = arranged)
        assertEquals(listOf(LibraryKind.Artists, LibraryKind.Albums), vm.state.value.kinds)
        assertEquals(LibraryKind.Artists, vm.state.value.kind)
        // Not held, but the only kind arranged: it still shows, as empty.
        arranged.value = listOf(LibraryKind.Podcasts)
        assertEquals(listOf(LibraryKind.Podcasts), vm.state.value.kinds)
        assertEquals(LibraryKind.Podcasts, vm.state.value.kind)
    }

    @Test fun oneKindAloneIsItsOwnPage() = runTest {
        sources[MediaType.Album to LibrarySort.RecentlyPlayed] = MutableStateFlow(listOf(entity(1, kind = "album")))
        val vm = viewModel(SavedStateHandle(mapOf("kind" to LibraryKind.Playlists.name)), only = LibraryKind.Albums)
        assertEquals(LibraryKind.Albums, vm.state.value.kind)
        assertEquals(listOf(LibraryKind.Albums), vm.state.value.kinds)
        assertTrue(vm.state.value.single)
        vm.selectKind(LibraryKind.Playlists)
        assertEquals(LibraryKind.Albums, vm.state.value.kind)
    }

    @Test fun downloadsShowWhileThePhoneHoldsSomeAndNeedNoServer() = runTest {
        val downloads = MutableStateFlow(emptyList<MediaUi>())
        session.value = SessionState.Offline("Home")
        val vm = viewModel(SavedStateHandle(mapOf("kind" to LibraryKind.Downloads.name)), downloads = downloads)
        assertEquals(LibraryKind.Playlists, vm.state.value.kind)
        val book = MediaUi("library://audiobook/3", MediaKind.Audiobook, "Das dicke Buch", "Testautorin · 312 MB")
        downloads.value = listOf(book)
        assertEquals(LibraryKind.Downloads, vm.state.value.kinds?.first())
        vm.selectKind(LibraryKind.Downloads)
        assertEquals(listOf(book), vm.state.value.items)
        assertFalse(vm.state.value.offline)
        vm.refresh()
        assertTrue(resyncs.isEmpty())
        downloads.value = emptyList()
        assertEquals(LibraryKind.Playlists, vm.state.value.kind)
    }

    @Test fun kindAndSortAreKeptInSavedState() = runTest {
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        vm.selectKind(LibraryKind.Albums)
        vm.sort(LibrarySort.AToZ)
        assertEquals(LibraryKind.Albums, vm.state.value.kind)
        assertEquals(LibrarySort.AToZ, vm.state.value.sort)

        asked.clear()
        val restored = viewModel(SavedStateHandle(mapOf("kind" to handle["kind"], "sort" to handle["sort"])))
        assertEquals(LibraryKind.Albums, restored.state.value.kind)
        assertEquals(LibrarySort.AToZ, restored.state.value.sort)
        assertEquals(MediaType.Album to LibrarySort.AToZ, asked.first())
    }

    @Test fun lettersOnlyInAToZ() = runTest {
        val names = listOf("Ada", "Basil", "Cleo") + List(30) { "Zed $it" }
        val entities = names.mapIndexed { i, name -> entity(i, name = name) }
        sources[MediaType.Playlist to LibrarySort.AToZ] = MutableStateFlow(entities)
        sources[MediaType.Playlist to LibrarySort.RecentlyAdded] = MutableStateFlow(entities)
        val vm = viewModel()
        vm.sort(LibrarySort.AToZ)
        assertEquals(listOf("A", "B", "C", "Z"), vm.state.value.letters.map { it.letter })
        vm.sort(LibrarySort.RecentlyAdded)
        assertEquals(emptyList<LetterJump>(), vm.state.value.letters)
    }

    @Test fun aChipSwitchesTogetherWithItsList() = runTest {
        sources[MediaType.Playlist to LibrarySort.RecentlyPlayed] = MutableStateFlow(listOf(entity(1)))
        sources[MediaType.Album to LibrarySort.RecentlyPlayed] = MutableStateFlow(listOf(entity(7, kind = "album")))
        val vm = viewModel()
        vm.selectKind(LibraryKind.Albums)
        val state = vm.state.value
        assertEquals(LibraryKind.Albums, state.kind)
        assertEquals(listOf("library://album/7"), state.items.map { it.key })
    }

    @Test fun aSlowListAnswersTheTapWithLoadingWithin100Ms() = runTest {
        sources[MediaType.Playlist to LibrarySort.RecentlyPlayed] = MutableStateFlow(listOf(entity(1)))
        sources[MediaType.Album to LibrarySort.RecentlyPlayed] = MutableSharedFlow() // never answers
        val vm = viewModel()
        vm.selectKind(LibraryKind.Albums)
        assertEquals(LibraryKind.Playlists, vm.state.value.kind)
        advanceTimeBy(101)
        val state = vm.state.value
        assertEquals(LibraryKind.Albums, state.kind)
        assertTrue(state.loading)
        assertTrue(state.items.isEmpty())
    }

    @Test fun aReconnectAttemptShowsLoadingThenOfflineAgain() = runTest {
        session.value = SessionState.Offline("Home")
        val vm = viewModel()
        assertTrue(vm.state.value.offline)
        session.value = SessionState.Waiting("Home")
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.offline)
        session.value = SessionState.Offline("Home")
        runCurrent()
        assertTrue(vm.state.value.offline)
    }

    @Test fun albumsKnowTheirArtist() = runTest {
        sources[MediaType.Album to LibrarySort.RecentlyPlayed] =
            MutableStateFlow(
                listOf(entity(3, kind = "album", artist = "library://artist/9"), entity(4, kind = "album")),
            )
        val vm = viewModel(SavedStateHandle(mapOf("kind" to "Albums")))
        val (withArtist, without) = vm.state.value.items
        assertEquals("library://artist/9", withArtist.artistKey)
        assertNull(without.artistKey)
    }

    @Test fun aPullSyncsTheChipsKindAndSpinsUntilItAnswers() = runTest {
        sources[MediaType.Album to LibrarySort.RecentlyPlayed] = MutableStateFlow(listOf(entity(3, kind = "album")))
        val vm = viewModel(SavedStateHandle(mapOf("kind" to "Albums")))
        vm.refresh()
        vm.refresh()
        assertEquals(listOf(MediaType.Album), resyncs)
        assertTrue(vm.state.value.refreshing)
        assertFalse(vm.state.value.loading)

        sync.complete(Unit)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun aPullSpinsOnItsOwnKindAndAnotherKindPullsToo() = runTest {
        sources[MediaType.Album to LibrarySort.RecentlyPlayed] = MutableStateFlow(listOf(entity(3, kind = "album")))
        val vm = viewModel(SavedStateHandle(mapOf("kind" to "Albums")))
        vm.refresh()
        vm.selectKind(LibraryKind.Playlists)
        assertFalse(vm.state.value.refreshing)
        vm.refresh()
        assertEquals(listOf(MediaType.Album, MediaType.Playlist), resyncs)
        assertTrue(vm.state.value.refreshing)
    }

    @Test fun aPullOnAnEmptyListKeepsItsSentenceUnderTheSpinner() = runTest {
        val vm = viewModel()
        assertTrue(vm.state.value.isEmpty)
        vm.refresh()
        assertTrue(vm.state.value.refreshing)
        assertTrue(vm.state.value.isEmpty)
    }

    @Test fun aFailedPullSaysSoAndOffersRetry() = runTest {
        val vm = viewModel()
        vm.refresh()
        sync.completeExceptionally(MaConnectionException("offline"))
        assertFalse(vm.state.value.refreshing)
        val shown = messages.pending.value
        assertEquals(R.string.library_refresh_failed, shown.single().text)

        sync = CompletableDeferred()
        shown.single().retry?.invoke()
        assertEquals(2, resyncs.size)
        assertTrue(vm.state.value.refreshing)
    }

    @Test fun aKindWhoseSyncFailedSaysWhyAndRetrySyncsItAgain() = runTest {
        val vm = viewModel()
        failures.value = mapOf(MediaType.Playlist to MaCommandException("music/playlists/library_items", 1, "Boom"))
        assertEquals(PlaybackR.string.error_generic, vm.state.value.failure)
        assertFalse(vm.state.value.isEmpty)
        vm.retry()
        assertEquals(listOf(MediaType.Playlist), resyncs)
        assertTrue(vm.state.value.loading)
    }

    private val playlists = LibrarySelection(LibraryKind.Playlists, LibrarySort.RecentlyPlayed)
    private val one = listOf(MediaUi("library://playlist/1", MediaKind.Playlist, "Mornings"))

    @Test fun anEmptyListWhileSyncingOrConnectingIsTheFirstLoad() {
        val empty = LibraryPage(playlists, emptyList())
        assertTrue(libraryState(empty, syncing = true, ServerStatus.Connected, synced = false).loading)
        // Once synced, an empty kind stays empty during a re-sync or a reconnect.
        assertFalse(libraryState(empty, syncing = true, ServerStatus.Connected, synced = true).loading)
        assertFalse(libraryState(empty, syncing = false, ServerStatus.Connecting, synced = true).loading)
        assertTrue(libraryState(empty, syncing = false, ServerStatus.Connecting, synced = false).loading)
        assertTrue(
            libraryState(
                LibraryPage(playlists, items = null),
                syncing = false,
                ServerStatus.Offline,
                synced = false,
            ).loading,
        )
    }

    @Test fun anEmptyListOfflineIsUnreachable() {
        val state =
            libraryState(LibraryPage(playlists, emptyList()), syncing = true, ServerStatus.Offline, synced = false)
        assertTrue(state.offline)
        assertFalse(state.loading)
    }

    @Test fun anEmptyListOnlineAndSyncedIsEmpty() {
        assertTrue(
            libraryState(
                LibraryPage(playlists, emptyList()),
                syncing = false,
                ServerStatus.Connected,
                synced = false,
            ).isEmpty,
        )
    }

    @Test fun cachedItemsAlwaysShow() {
        val state = libraryState(LibraryPage(playlists, one), syncing = true, ServerStatus.Offline, synced = false)
        assertEquals(one, state.items)
        assertFalse(state.loading)
        assertFalse(state.offline)
    }

    @Test fun unknownSavedValuesFallBackToTheDefaults() {
        assertEquals(playlists, selectionOf("Genres", null))
    }
}
