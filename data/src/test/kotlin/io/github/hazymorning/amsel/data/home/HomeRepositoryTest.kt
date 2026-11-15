package io.github.hazymorning.amsel.data.home

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.hazymorning.amsel.api.MaConnectionException
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private val snapshotFile get() = File(folder.root, "home.json")

    /** A loader that answers each option with one item named after it, and records what it was asked. */
    private class FakeLoader(private val speaker: () -> String? = { null }) : SectionLoader {
        val asked = CopyOnWriteArrayList<SectionOption>()
        var failing = emptySet<SectionOption>()
        var round = 0

        override suspend fun load(
            option: SectionOption,
            limit: Int,
            pinned: List<String>,
            previous: SectionItems?,
        ): SectionItems {
            asked += option
            if (option in failing) throw MaConnectionException("offline")
            if (option == SectionOption.Pinned) return SectionItems(pinned.map { HomeItem(it, "album", it) })
            val here = speaker().takeIf { option == SectionOption.PlayedHere }
            return SectionItems(
                listOf(HomeItem("library://album/${option.name}/$round", "album", option.name)),
                speaker = here,
            )
        }
    }

    private class Env(val repo: HomeRepository, val loader: FakeLoader, val serverId: MutableStateFlow<String?>)

    /** A repository over a real DataStore and snapshot file in [folder], closed after [block]. */
    private fun withRepo(
        server: String? = "server-a",
        loader: FakeLoader = FakeLoader(),
        speakers: MutableStateFlow<String?> = MutableStateFlow(null),
        block: suspend (Env) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                File(folder.root, "home.preferences_pb")
            }
            val ids = MutableStateFlow(server)
            val repo = HomeRepository(loader, HomeLayoutStore(store), snapshotFile, ids, scope, speakers)
            withTimeout(10_000) { block(Env(repo, loader, ids)) }
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test fun nothingCachedUntilTheFirstRefresh() = withRepo { env ->
        env.repo.load()
        assertNull(env.repo.items.value)
        assertEquals(HomeLayout(), env.repo.layout.value)
    }

    @Test fun continueListeningJoinsAHomeMadeBeforeItOnce() = withRepo { env ->
        env.repo.load()
        env.repo.edit { it.remove("s0").copy(listeningOffered = false) }
        env.repo.offerListening()
        val offered = env.repo.layout.first { it?.listeningOffered == true }!!
        assertEquals(SectionOption.ContinueListening, offered.sections.first().option)
        assertTrue(SectionOption.ContinueListening in env.loader.asked)
        env.repo.edit { it.remove(offered.sections.first().id) }
        val listening = { layout: HomeLayout? ->
            layout!!.sections.any { it.option == SectionOption.ContinueListening }
        }
        env.repo.layout.first { !listening(it) }
        env.repo.offerListening()
        assertFalse(listening(env.repo.layout.value))
    }

    @Test fun playedHereLoadsAgainWhenAnotherSpeakerIsPicked() {
        val inUse = MutableStateFlow<String?>("kitchen")
        withRepo(loader = FakeLoader { inUse.value }, speakers = inUse) { env ->
            env.repo.load()
            env.repo.edit { it.setOption("s2", SectionOption.PlayedHere) }
            assertEquals("kitchen", env.repo.items.value!![SectionOption.PlayedHere]!!.speaker)
            inUse.value = "living-room"
            env.repo.items.first { it?.get(SectionOption.PlayedHere)?.speaker == "living-room" }
        }
    }

    @Test fun refreshLoadsEveryOptionOnHome() = withRepo { env ->
        env.repo.load()
        env.repo.refresh()
        val items = env.repo.items.value!!
        assertEquals(HomeLayout().sections.map { it.option }.toSet(), items.keys)
        assertEquals("NewAlbums", items.getValue(SectionOption.NewAlbums).items.single().name)
    }

    @Test fun theNextLaunchPaintsTheSnapshotBeforeAnyRefresh() {
        withRepo { env ->
            env.repo.load()
            env.repo.refresh()
        }
        withRepo { env ->
            env.repo.load()
            assertEquals(5, env.repo.items.value?.size)
            assertTrue(env.loader.asked.isEmpty())
        }
    }

    @Test fun anotherServersSnapshotIsNotShown() {
        withRepo("server-a") { env ->
            env.repo.load()
            env.repo.refresh()
        }
        withRepo("server-b") { env ->
            env.repo.load()
            assertNull(env.repo.items.value)
        }
    }

    @Test fun loadingDoesNotWaitWhenNoOneIsSignedIn() = withRepo(server = null) { env ->
        env.repo.load()
        assertNull(env.repo.items.value)
    }

    @Test fun aSectionThatFailsKeepsWhatItHad() = withRepo { env ->
        env.repo.load()
        env.repo.refresh()
        val before = env.repo.items.value!!.getValue(SectionOption.Everything)
        env.loader.failing = setOf(SectionOption.Everything)
        env.loader.round = 1
        // The failure is reported once every section has loaded.
        assertThrows(MaConnectionException::class.java) { runBlocking { env.repo.refresh() } }
        assertEquals(before, env.repo.items.value!!.getValue(SectionOption.Everything))
        assertNotEquals(before, env.repo.items.value!!.getValue(SectionOption.NewAlbums))
    }

    @Test fun aFirstLoadThatFailsIsEmptyNotLoadingForEver() = withRepo { env ->
        env.loader.failing = setOf(SectionOption.SmartPlaylists)
        env.repo.load()
        assertThrows(MaConnectionException::class.java) { runBlocking { env.repo.refresh() } }
        assertEquals(SectionItems(), env.repo.items.value!!.getValue(SectionOption.SmartPlaylists))
    }

    @Test fun aRetryAfterAnEditLoadsOnlyWhatFailed() = withRepo { env ->
        env.repo.load()
        env.repo.refresh()
        env.loader.failing = setOf(SectionOption.TopArtists)
        assertThrows(MaConnectionException::class.java) {
            runBlocking { env.repo.edit { it.setOption("s4", SectionOption.TopArtists) } }
        }
        env.loader.failing = emptySet()
        env.loader.asked.clear()
        env.repo.reloadFailed()
        assertEquals(listOf(SectionOption.TopArtists), env.loader.asked.toList())
    }

    @Test fun randomAlbumsChangeOnlyWhenReshuffled() = withRepo { env ->
        env.repo.load()
        env.repo.edit { it.setOption("s4", SectionOption.RandomAlbums) }
        val first = env.repo.items.value!!.getValue(SectionOption.RandomAlbums)
        env.loader.round = 1
        env.repo.refresh()
        assertEquals(first, env.repo.items.value!!.getValue(SectionOption.RandomAlbums))
        env.repo.reshuffle()
        assertNotEquals(first, env.repo.items.value!!.getValue(SectionOption.RandomAlbums))
    }

    @Test fun editsAreStoredAndLoadOnlyWhatIsNew() = withRepo { env ->
        env.repo.load()
        env.repo.refresh()
        env.loader.asked.clear()
        env.repo.edit { it.setLayout("s2", SectionLayout.Big) }
        assertTrue(env.loader.asked.isEmpty())
        env.repo.edit { it.setOption("s3", SectionOption.TopArtists) }
        assertEquals(listOf(SectionOption.TopArtists), env.loader.asked)
        val layout = env.repo.layout.first { it?.sections?.any { s -> s.option == SectionOption.TopArtists } == true }!!
        assertEquals(SectionLayout.Big, layout.sections.single { it.id == "s2" }.layout)
    }

    @Test fun refreshIndexedLeavesTheServersSectionsAlone() = withRepo { env ->
        env.repo.load()
        env.repo.refreshIndexed()
        assertEquals(
            setOf(SectionOption.Everything, SectionOption.SmartPlaylists, SectionOption.NewAlbums),
            env.loader.asked.toSet(),
        )
    }

    @Test fun anOptionBroughtBackLoadsAfresh() = withRepo { env ->
        env.repo.load()
        env.repo.refresh()
        val option = env.repo.layout.value!!.sections.single { it.id == "s4" }.option
        env.repo.edit { it.setOption("s4", SectionOption.TopArtists) }
        assertFalse(option in env.repo.items.value!!)
        env.loader.round = 1
        env.repo.edit { it.setOption("s4", option) }
        assertEquals("library://album/${option.name}/1", env.repo.items.value!!.getValue(option).items.single().uri)
    }

    @Test fun pinningShowsThePinAndStoresIt() = withRepo { env ->
        env.repo.load()
        env.repo.edit { it.remove("s1") }
        env.repo.pin("library://album/7")
        val layout = env.repo.layout.first { it?.pinned?.isNotEmpty() == true }!!
        assertEquals(SectionOption.Pinned, layout.sections.first().option)
        assertTrue(env.repo.isPinned("library://album/7"))
        assertEquals(
            listOf("library://album/7"),
            env.repo.items.value!!.getValue(SectionOption.Pinned).items.map {
                it.uri
            },
        )
        env.repo.unpin("library://album/7")
        assertFalse(env.repo.layout.first { it?.pinned?.isEmpty() == true }!!.isPinned("library://album/7"))
        assertTrue(env.repo.items.value!!.getValue(SectionOption.Pinned).items.isEmpty())
    }

    @Test fun seeAllAsksForMoreWithoutTouchingTheSnapshot() = withRepo { env ->
        env.repo.load()
        val all = env.repo.all(SectionOption.NewAlbums, limit = 500)
        assertEquals(1, all.items.size)
        assertNull(env.repo.items.value)
    }

    @Test fun signingOutForgetsTheSnapshotButNotTheLayout() = withRepo { env ->
        env.repo.load()
        env.repo.edit { it.remove("s4") }
        env.repo.refresh()
        assertTrue(snapshotFile.exists())
        env.repo.clear()
        assertNull(env.repo.items.value)
        assertFalse(snapshotFile.exists())
        assertEquals(4, env.repo.layout.value?.sections?.size)
    }

    @Test fun aSnapshotFromBeforeArtistsWoreCoversStillPaints() {
        val artist = """{"uri": "library://artist/3", "kind": "artist", "name": "Aurora Tone"}"""
        snapshotFile.writeText("""{"serverId": "server-a", "sections": {"FavouriteArtists": {"items": [$artist]}}}""")
        withRepo { env ->
            env.repo.load()
            val shown = env.repo.items.value?.get(SectionOption.FavoriteArtists)?.items?.single()
            assertEquals("Aurora Tone", shown?.name)
            assertEquals(emptyList<String>(), shown?.covers)
        }
    }

    @Test fun theOldFixedHomeSnapshotIsIgnored() {
        snapshotFile.writeText("""{"jumpBackIn": [], "playlists": [], "recentlyAdded": []}""")
        withRepo { env ->
            env.repo.load()
            assertNull(env.repo.items.value)
        }
    }
}
