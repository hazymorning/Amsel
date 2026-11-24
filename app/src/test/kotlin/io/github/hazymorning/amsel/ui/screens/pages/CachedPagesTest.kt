package io.github.hazymorning.amsel.ui.screens.pages

import io.github.hazymorning.amsel.api.EPISODE_PAGE
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.app.MessageController
import io.github.hazymorning.amsel.data.CachedPage
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/** A page opened again paints what it showed last time while the server is asked. */
@OptIn(ExperimentalCoroutinesApi::class)
class CachedPagesTest {
    private val messages = MessageController(MutableStateFlow(true))
    private val mapper = UiMapper { null }
    private val playing = MutableStateFlow<String?>(null)

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    @Test fun `an album opened again shows its tracks at once, then the server's`() = runTest {
        val tracks = list("music_albums_album_tracks")
        val source = FakePageSource().apply {
            item = one("music_albums_get")
            this.tracks = tracks
        }
        watch(AlbumViewModel(ALBUM, source, mapper, playing, messages).state)
        assertEquals(tracks, source.shownPages.getValue(ALBUM).rows)

        source.close()
        source.tracks = tracks.take(1)
        val state = watch(AlbumViewModel(ALBUM, source, mapper, playing, messages).state)
        assertEquals("First Light", state.value.album?.title)
        assertEquals(3, state.value.tracks.size)
        assertFalse(state.value.tracksLoading)

        source.open()
        advanceUntilIdle()
        assertEquals(listOf("library://track/10"), state.value.tracks.map { it.key })
        assertEquals(listOf("library://track/10"), source.shownPages.getValue(ALBUM).rows.map { it.uri })
    }

    @Test fun `an album the server cannot give now keeps what it showed last time, with Retry`() = runTest {
        val source = FakePageSource().apply {
            shownPages[ALBUM] = CachedPage(one("music_albums_get"), list("music_albums_album_tracks"))
            failure = MaConnectionException("offline")
        }
        val state = watch(AlbumViewModel(ALBUM, source, mapper, playing, messages).state)
        assertEquals(3, state.value.tracks.size)
        assertFalse(state.value.showUnavailable)
        assertTrue(messages.pending.value.single().retry != null)
    }

    @Test fun `a playlist's remembered tracks show at once, but nothing is removed by them`() = runTest {
        val source = FakePageSource(gated = true).apply {
            shownPages[PLAYLIST] = CachedPage(one("music_playlists_get"), list("music_playlists_playlist_tracks"))
            item = one("music_playlists_get")
            tracks = list("music_playlists_playlist_tracks").take(2)
        }
        val worker = UnconfinedTestDispatcher(testScheduler)
        val state = watch(PlaylistViewModel(PLAYLIST, source, mapper, playing, emptyFlow(), messages, worker).state)
        assertEquals(listOf(1, 2, 3, 4, 5), state.value.positions)
        assertFalse(state.value.complete)

        source.open()
        advanceUntilIdle()
        assertEquals(listOf(1, 2), state.value.positions)
        assertTrue(state.value.complete)
        assertEquals(2, source.shownPages.getValue(PLAYLIST).rows.size)
    }

    @Test fun `an artist opened again shows its popular songs and albums at once`() = runTest {
        val source = FakePageSource(gated = true).apply {
            shownPages[ARTIST] = CachedPage(
                one("music_artists_get"),
                rows = list("music_artists_artist_tracks").take(5),
                shelf = list("music_artists_artist_albums"),
            )
        }
        val state = watch(ArtistViewModel(ARTIST, source, mapper, playing, messages).state)
        assertEquals("Aurora Tone", state.value.artist?.title)
        assertEquals(5, state.value.popular.size)
        assertTrue(state.value.albums.isNotEmpty())
        assertFalse(state.value.contentLoading)
        assertFalse(state.value.popularPending)
    }

    @Test fun `a podcast opened again shows its newest episodes at once, and asks for more only after the server`() =
        runTest {
            val episodes = list("music_podcasts_podcast_episodes__page")
            val source = FakePageSource(gated = true).apply {
                shownPages[PODCAST] = CachedPage(one("music_podcasts_get"), episodes)
                item = one("music_podcasts_get")
                pages = listOf(List(EPISODE_PAGE) { i -> episodes[i % 2].copy(uri = "${episodes[i % 2].uri}#$i") })
            }
            val vm = PodcastViewModel(PODCAST, source, mapper, playing, messages)
            val state = watch(vm.state)
            assertEquals("Harbour Talk", state.value.podcast?.title)
            assertEquals(episodes.size, state.value.episodes.size)
            vm.loadMore()
            assertTrue(source.asked.isEmpty())

            source.open()
            advanceUntilIdle()
            assertEquals(EPISODE_PAGE, state.value.episodes.size)
        }

    @Test fun `episodes shown from last time ask for no more until the server has answered`() = runTest {
        val episodes = list("music_podcasts_podcast_episodes__page")
        val source = FakePageSource().apply {
            shownPages[PODCAST] = CachedPage(one("music_podcasts_get"), episodes)
            failure = MaConnectionException("offline")
        }
        val vm = PodcastViewModel(PODCAST, source, mapper, playing, messages)
        val state = watch(vm.state)
        assertEquals(episodes.size, state.value.episodes.size)

        source.failure = null
        vm.loadMore()
        assertTrue(source.asked.isEmpty())
    }

    @Test fun `a page opened for the first time has nothing to show before the server`() = runTest {
        val source = FakePageSource(gated = true)
        val state = watch(AlbumViewModel(ALBUM, source, mapper, playing, messages).state)
        assertNull(state.value.album)
        assertTrue(state.value.tracksLoading)
    }

    private companion object {
        const val PLAYLIST = "library://playlist/9"
        const val PODCAST = "library://podcast/1"
    }
}
