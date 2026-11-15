package io.github.hazymorning.amsel.ui.screens.home

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.app.Messages
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.data.home.HomeItem
import io.github.hazymorning.amsel.data.home.HomeLayout
import io.github.hazymorning.amsel.data.home.HomeSource
import io.github.hazymorning.amsel.data.home.SectionItems
import io.github.hazymorning.amsel.data.home.SectionLayout
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.SpokenWord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val mapper = UiMapper { null }
    private val ready = SessionState.Ready(ServerInfo("s1", "2.10.9", 28), user = null)
    private val offline = SessionState.Offline("Home")

    private fun item(kind: String, n: Int, artist: String? = null) =
        HomeItem("library://$kind/$n", kind, "$kind $n", artistUri = artist)

    private fun items(vararg list: HomeItem, fallback: Boolean = false) = SectionItems(list.toList(), fallback)

    private val snapshot = mapOf(
        SectionOption.Pinned to items(),
        SectionOption.Everything to items(item("album", 1), item("playlist", 2)),
        SectionOption.SmartPlaylists to items(item("playlist", 3)),
        SectionOption.NewAlbums to items(item("album", 4, artist = "library://artist/9")),
    )

    /** A HomeSource that shows an edit at once, as HomeRepository does, and stores it when [release] completes. */
    private class FakeHome(
        layout: HomeLayout? = classicLayout(),
        items: Map<SectionOption, SectionItems>? = null,
        val release: CompletableDeferred<Unit>? = null,
    ) : HomeSource {
        override val layout = MutableStateFlow(layout)
        override val items = MutableStateFlow(items)
        override val changes = emptyFlow<SectionOption>()

        override suspend fun edit(change: (HomeLayout) -> HomeLayout) {
            layout.value = change(layout.value ?: classicLayout())
            release?.await()
        }

        override suspend fun refresh() = Unit

        override suspend fun reloadFailed() = Unit
    }

    private fun viewModel(
        home: HomeSource,
        session: StateFlow<SessionState> = MutableStateFlow(ready),
        syncing: Boolean = false,
        uiMapper: UiMapper = mapper,
        libraryEmpty: Flow<Boolean> = MutableStateFlow(false),
    ) = HomeViewModel(
        SavedStateHandle(),
        home,
        session,
        MutableStateFlow(syncing),
        MutableStateFlow(emptySet<MediaType>()),
        libraryEmpty,
        uiMapper,
        Messages(MutableStateFlow(true)),
    )

    /** A ViewModel whose state is collected for the rest of the test, as the screen collects it. */
    private fun TestScope.subscribed(
        home: HomeSource,
        libraryEmpty: Flow<Boolean> = MutableStateFlow(false),
    ): HomeViewModel = viewModel(home, libraryEmpty = libraryEmpty).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }

    private fun state(
        items: Map<SectionOption, SectionItems>?,
        layout: HomeLayout? = classicLayout(),
        offline: Boolean = false,
        firstSyncs: Set<MediaType> = emptySet(),
        editing: Boolean = false,
    ) = homeState(HomeInputs(layout, items, offline, firstSyncs), editing, mapper::media)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun everySectionPaintsFromItsSnapshotInOrder() {
        val state = state(snapshot)
        assertEquals(classicLayout().sections.map { it.option }, state.sections.map { it.option })
        val recent = state.sections[1]
        assertEquals(listOf("library://album/1", "library://playlist/2"), recent.items.map { it.key })
        assertEquals(SectionLayout.Row, recent.layout)
        assertFalse(recent.loading)
        assertFalse(state.loading)
        assertTrue(state.hasContent)
    }

    @Test fun anEmptySectionIsHiddenOutsideEditMode() {
        val state = state(snapshot)
        assertEquals(listOf("s2", "s3", "s4"), state.visible.map { it.id })
        assertEquals(listOf("s1", "s2", "s3", "s4"), state(snapshot, editing = true).visible.map { it.id })
    }

    @Test fun spokenWordOptionsAreOfferedOnlyWhileTheLibraryHoldsSome() {
        assertFalse(SectionOption.ContinueListening.isOffered(SpokenWord()))
        assertTrue(SectionOption.ContinueListening.isOffered(SpokenWord(podcasts = true)))
        assertTrue(SectionOption.NewAudiobooks.isOffered(SpokenWord(books = true)))
        assertFalse(SectionOption.NewEpisodes.isOffered(SpokenWord(books = true)))
        assertTrue(SectionOption.NewAlbums.isOffered(SpokenWord()))
    }

    @Test fun pinnedWithoutPinsNeverLoads() {
        val pinned = state(null).sections.first()
        assertFalse(pinned.loading)
        assertTrue(pinned.isEmpty)
    }

    @Test fun nothingCachedLoadsWhileConnecting() {
        val state = state(null, offline = isOffline(SessionState.Waiting("Home")))
        assertTrue(state.loading)
        assertEquals(listOf("s2", "s3", "s4"), state.visible.map { it.id })
        assertTrue(state.visible.all { it.loading })
        assertTrue(state(null, offline = isOffline(ready)).loading)
        // Editing keeps every section's options and Add section reachable, loaded or not.
        assertFalse(state.copy(editing = true).loading)
    }

    @Test fun nothingCachedAndOfflineShowsNothingToLoad() {
        val state = state(null, offline = isOffline(offline))
        assertTrue(state.offline)
        assertFalse(state.loading)
        assertTrue(state.visible.isEmpty())
    }

    @Test fun anOptionNewToHomeLoadsWhileTheOthersShow() {
        val layout = classicLayout().setOption("s3", SectionOption.TopArtists)
        val state = state(snapshot, layout)
        assertTrue(state.sections[2].loading)
        assertFalse(state.sections[1].loading)
        assertFalse(state.loading)
    }

    @Test fun indexSectionsLoadWhileTheFirstSyncRuns() {
        val empty = snapshot + (SectionOption.NewAlbums to items())
        assertTrue(state(empty, firstSyncs = setOf(MediaType.Album)).sections[3].loading)
        assertTrue(state(empty).sections[3].isEmpty)
        // Albums synced before: a sync of another kind leaves their empty section empty.
        assertTrue(state(empty, firstSyncs = setOf(MediaType.Playlist)).sections[3].isEmpty)
        val noRecent = snapshot + (SectionOption.RecentAlbums to items())
        val server = classicLayout().setOption("s4", SectionOption.RecentAlbums)
        assertTrue(state(noRecent, server, firstSyncs = setOf(MediaType.Album)).sections[3].isEmpty)
    }

    @Test fun eachLayoutShowsItsShareAndOffersSeeAllBeyondIt() {
        val many = (1..13).map { item("album", it) }
        val all = mapOf(SectionOption.NewAlbums to SectionItems(many))
        fun section(layout: SectionLayout) =
            state(all, classicLayout().setLayout("s4", layout)).sections.single { it.id == "s4" }
        assertEquals(12, section(SectionLayout.Row).items.size)
        assertEquals(12, section(SectionLayout.Small).items.size)
        assertEquals(8, section(SectionLayout.Grid).items.size)
        assertEquals(8, section(SectionLayout.Big).items.size)
        assertEquals(5, section(SectionLayout.List).items.size)
        assertTrue(SectionLayout.entries.all { section(it).more })
        val few = mapOf(SectionOption.NewAlbums to SectionItems(many.take(3)))
        assertFalse(state(few).sections[3].more)
    }

    @Test fun anUnpinnedItemLeavesBeforeTheSectionReloads() {
        val layout = classicLayout().pin("library://album/1").pin("library://album/2").unpin("library://album/1")
        val cached = mapOf(SectionOption.Pinned to items(item("album", 2), item("album", 1)))
        assertEquals(listOf("library://album/2"), state(cached, layout).sections.first().items.map { it.key })
    }

    @Test fun movedPinsMoveBeforeTheSectionReloads() {
        val pins = listOf("library://album/1", "library://album/2", "library://album/3")
        val layout = classicLayout().pin(pins[2]).pin(pins[1]).pin(pins[0]).arrangePins(pins.reversed())
        val cached = mapOf(SectionOption.Pinned to items(item("album", 1), item("album", 2), item("album", 3)))
        assertEquals(pins.reversed(), state(cached, layout).sections.first().items.map { it.key })
    }

    @Test fun editModeShowsEveryPinSoEachCanBeMoved() {
        val pins = (1..10).map { "library://album/$it" }
        val layout = pins.reversed().fold(classicLayout()) { home, pin -> home.pin(pin) }
        val cached = mapOf(SectionOption.Pinned to SectionItems(pins.map { HomeItem(it, "album", it) }))
        assertEquals(8, state(cached, layout).sections.first().items.size)
        assertEquals(pins, state(cached, layout, editing = true).sections.first().items.map { it.key })
    }

    @Test fun recentlyPlayedFallsBackToYourAlbums() {
        val fallback = snapshot + (SectionOption.Everything to items(item("album", 7), fallback = true))
        val recent = state(fallback).sections[1]
        assertTrue(recent.fallback)
        assertEquals(R.string.home_your_albums, sectionTitle(recent.option, recent.fallback))
        assertEquals(PlaybackR.string.recently_played, sectionTitle(recent.option, fallback = false))
    }

    @Test fun playedHereNamesTheSpeakerItsPlaysAreFrom() {
        val layout = classicLayout().setOption("s2", SectionOption.PlayedHere)
        val kitchen = SpeakerUi("kitchen", "Kitchen")
        val played = mapOf(SectionOption.PlayedHere to SectionItems(listOf(item("album", 7)), speaker = "kitchen"))
        val whole = mapOf(SectionOption.PlayedHere to SectionItems(listOf(item("album", 7))))
        val inputs = { items: Map<SectionOption, SectionItems> ->
            HomeInputs(layout, items, offline = false, firstSyncs = emptySet(), HomeSpeakers(listOf(kitchen)))
        }
        val shown = { items: Map<SectionOption, SectionItems> ->
            homeState(inputs(items), false, mapper::media).sections.single { it.option == SectionOption.PlayedHere }
        }
        assertEquals(kitchen, shown(played).heardOn)
        assertEquals(null, shown(whole).heardOn)
    }

    @Test fun speakersAreThoseThatPlayOrRestOnSomethingTheOneInUseIncluded() {
        val layout = classicLayout().setOption("s2", SectionOption.Speakers)
        val phone = SpeakerUi("phone", "", isPhone = true, song = "Lanterns")
        val kitchen = SpeakerUi("kitchen", "Kitchen", song = "Lanterns", isPlaying = true)
        val bedroom = SpeakerUi("bedroom", "Bedroom", song = "Cooking")
        val garden = SpeakerUi("garden", "Garden")
        val shelf = { speakers: HomeSpeakers ->
            homeState(HomeInputs(layout, emptyMap(), false, emptySet(), speakers), false, mapper::media)
                .sections.single { it.option == SectionOption.Speakers }
        }
        val home = shelf(HomeSpeakers(listOf(phone, kitchen, bedroom, garden), inUse = phone))
        assertEquals(listOf(phone, kitchen, bedroom), home.speakers)
        assertFalse(home.loading)
        assertTrue(shelf(HomeSpeakers(listOf(phone.copy(song = null), garden), inUse = phone)).isEmpty)
        assertTrue(shelf(HomeSpeakers(listOf(phone.copy(song = null), kitchen.copy(available = false)))).isEmpty)
        // Every speaker grouped under the one in use: the row shows that group, not nothing.
        val livingRoom = SpeakerUi("living", "Living room", song = "Lanterns", isPlaying = true, others = 5)
        val grouped = shelf(HomeSpeakers(listOf(phone.copy(song = null), livingRoom), inUse = livingRoom))
        assertEquals(listOf(livingRoom), grouped.speakers)
        assertEquals(SectionLayout.Row, layout.sections.single { it.option == SectionOption.Speakers }.layout)
    }

    @Test fun untilTheServerNamesItsSpeakersTheirRowHoldsItsRoomSoNothingUnderItMoves() {
        val layout = classicLayout().setOption("s2", SectionOption.Speakers)
        val shelf = { speakers: HomeSpeakers, offline: Boolean ->
            homeState(HomeInputs(layout, emptyMap(), offline, emptySet(), speakers), false, mapper::media)
                .sections.single { it.option == SectionOption.Speakers }
        }
        val unknown = shelf(HomeSpeakers(loaded = false), false)
        assertTrue(unknown.loading)
        assertFalse(unknown.isEmpty)
        // Out of reach nothing will answer: no room is held for it.
        assertTrue(shelf(HomeSpeakers(loaded = false), true).isEmpty)
        assertTrue(shelf(HomeSpeakers(loaded = true), false).isEmpty)
    }

    @Test fun theCachedSnapshotPaintsTheFirstFrame() {
        val vm = viewModel(FakeHome(items = snapshot), session = MutableStateFlow(SessionState.Waiting(null)))
        // No subscriber yet: the initial value already has the snapshot, so no skeleton flash.
        assertTrue(vm.state.value.hasContent)
        assertFalse(vm.state.value.loading)
    }

    @Test fun followsTheSnapshotAndTheConnection() = runTest {
        val home = FakeHome()
        val session = MutableStateFlow<SessionState>(SessionState.Waiting("Home"))
        val vm = viewModel(home, session)
        vm.state.test {
            assertTrue(awaitItem().loading)
            session.value = offline
            assertTrue(awaitItem().offline)
            session.value = ready
            assertTrue(awaitItem().loading)
            home.items.value = snapshot
            assertEquals(3, awaitItem().visible.size)
        }
    }

    @Test fun moreOpensTheSheetWithTheAlbumsArtist() {
        val vm = viewModel(FakeHome(items = snapshot))
        val album = vm.state.value.sections[3].items.single()
        assertEquals("library://artist/9", album.artistKey)
    }

    @Test fun editModeStartsAndEnds() = runTest {
        val vm = subscribed(FakeHome(items = snapshot))
        vm.startEditing()
        assertTrue(vm.state.value.editing)
        assertEquals(4, vm.state.value.visible.size)
        vm.doneEditing()
        assertFalse(vm.state.value.editing)
    }

    @Test fun everyEditShowsBeforeTheStoreHasWrittenIt() = runTest {
        val release = CompletableDeferred<Unit>()
        val home = FakeHome(items = snapshot, release = release)
        val vm = subscribed(home)
        vm.setLayout("s3", SectionLayout.Big)
        vm.moveUp("s3")
        assertEquals(listOf("s1", "s3", "s2", "s4"), vm.state.value.sections.map { it.id })
        assertEquals(SectionLayout.Big, vm.state.value.sections[1].layout)
        release.complete(Unit)
        assertEquals(home.layout.value?.sections?.map { it.id }, vm.state.value.sections.map { it.id })
        assertEquals(SectionLayout.Big, home.layout.value?.sections?.get(1)?.layout)
    }

    @Test fun everyEditOperationReachesTheStore() = runTest {
        val home = FakeHome(items = snapshot)
        val vm = subscribed(home)
        vm.setOption("s4", SectionOption.FavoriteAlbums)
        vm.setLayout("s4", SectionLayout.List)
        vm.moveDown("s2")
        vm.move("s4", 0)
        vm.remove("s1")
        vm.add()
        val expected = classicLayout()
            .setOption("s4", SectionOption.FavoriteAlbums)
            .setLayout("s4", SectionLayout.List)
            .moveDown("s2")
            .move("s4", 0)
            .remove("s1")
            .add()
        assertEquals(expected, home.layout.value)
        assertEquals(expected.sections.map { it.id }, vm.state.value.sections.map { it.id })
    }

    @Test fun homeGoesOnTakingSectionsPastSix() = runTest {
        val vm = subscribed(FakeHome(items = snapshot))
        repeat(4) { vm.add() }
        assertEquals(8, vm.state.value.sections.size)
    }

    @Test fun aHomeWithNothingToShowAsksWhetherTheLibraryIsEmpty() = runTest {
        val nothing = snapshot.mapValues { items() }
        assertEquals(true, subscribed(FakeHome(items = nothing), MutableStateFlow(true)).state.value.libraryEmpty)
        val vm = subscribed(FakeHome(items = nothing), MutableStateFlow(false))
        assertTrue(vm.state.value.showsNothing)
        assertEquals(false, vm.state.value.libraryEmpty)
        vm.startEditing()
        assertFalse(vm.state.value.showsNothing)
    }

    @Test fun aHomeWithSomethingToShowNeverAsksTheIndex() = runTest {
        val index = MutableStateFlow(false)
        val vm = subscribed(FakeHome(items = snapshot), index)
        assertFalse(vm.state.value.showsNothing)
        assertEquals(0, index.subscriptionCount.value)
    }
}

/** The defaults without Continue listening: the four sections these tests were written for. */
private fun classicLayout() = HomeLayout().remove("s0")
