package io.github.hazymorning.amsel.ui.screens.pages

import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.app.Messages
import io.github.hazymorning.amsel.app.PlaylistEdit
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
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

/** The pages' ViewModels against the recorded 2.11 server shapes. */
@OptIn(ExperimentalCoroutinesApi::class)
class PagesViewModelTest {
    private val messages = Messages(MutableStateFlow(true))
    private val mapper = UiMapper { null }
    private val playing = MutableStateFlow<String?>(null)

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    private fun TestScope.worker() = UnconfinedTestDispatcher(testScheduler)

    @Test fun `an album paints its cached header, then tracks and more by its artist`() = runTest {
        val source = FakeSource(gated = true).apply {
            rows.value =
                row(
                    "library://album/4",
                    "album",
                    "First Light",
                    subtitle = "Aurora Tone",
                    artist = "library://artist/3",
                )
            item = one("music_albums_get")
            tracks = list("music_albums_album_tracks")
            albums = list("music_artists_artist_albums")
        }
        val state = watch(AlbumViewModel(ALBUM, source, mapper, playing, messages).state)

        val cached = state.value
        assertEquals("First Light", cached.album?.title)
        assertEquals("Aurora Tone", cached.album?.artist)
        assertTrue(cached.tracksLoading)
        assertFalse(cached.showUnavailable)

        source.open()
        advanceUntilIdle()
        val loaded = state.value
        assertEquals(cached.album?.copy(year = 2021), loaded.album)
        assertEquals(
            listOf("library://track/10", "library://track/9", "library://track/8"),
            loaded.tracks.map {
                it.key
            },
        )
        assertEquals(listOf(1, 2, 3), loaded.tracks.map { it.number })
        assertEquals(45, loaded.durationSec)
        // The album itself is left out of "More by"; the rest carry their year.
        assertEquals(listOf("Second Wind" to "2023"), loaded.moreByArtist.map { it.title to it.subtitle })
        assertFalse(loaded.loading)
    }

    @Test fun `an album made a favorite anywhere else is one on its open page`() = runTest {
        val indexed = row("library://album/4", "album", "First Light")
        val source = FakeSource().apply {
            rows.value = indexed
            item = one("music_albums_get")
            tracks = list("music_albums_album_tracks")
        }
        val state = watch(AlbumViewModel(ALBUM, source, mapper, playing, messages).state)
        assertFalse(state.value.album?.media?.favorite == true)

        source.rows.value = indexed.copy(favorite = true)
        assertTrue(state.value.album?.media?.favorite == true)
    }

    @Test fun `an album that no longer exists says so, and Retry loads it again`() = runTest {
        val source = FakeSource().apply { failure = MaCommandException("music/item_by_uri", 2, "not found") }
        val vm = AlbumViewModel(ALBUM, source, mapper, playing, messages)
        val state = watch(vm.state)
        assertTrue(state.value.showUnavailable)

        source.failure = null
        source.item = one("music_albums_get")
        source.tracks = list("music_albums_album_tracks")
        vm.retry()
        advanceUntilIdle()
        assertFalse(state.value.showUnavailable)
        assertEquals(3, state.value.songCount)
    }

    @Test fun `a missing item is unavailable even when the server answers`() = runTest {
        val state = watch(AlbumViewModel(ALBUM, FakeSource(), mapper, playing, messages).state)
        assertTrue(state.value.showUnavailable)
    }

    @Test fun `the playing track is highlighted`() = runTest {
        val source = FakeSource().apply {
            item = one("music_albums_get")
            tracks = list("music_albums_album_tracks")
        }
        val state = watch(AlbumViewModel(ALBUM, source, mapper, playing, messages).state)
        assertNull(state.value.currentTrackKey)
        playing.value = "library://track/9"
        assertEquals("library://track/9", state.value.currentTrackKey)
        playing.value = "library://track/10"
        assertEquals("library://track/10", state.value.currentTrackKey)
    }

    @Test fun `a playlist the server fills anew says so on its own page, from the index and from the server`() {
        val row = row("library://playlist/7", "playlist", "Infinite Mix (library)")
        assertFalse(mapper.playlistHeader(row).media.dynamic)
        assertTrue(mapper.playlistHeader(row.copy(isDynamic = true)).media.dynamic)
        val item = one("music_playlists_get")
        assertFalse(mapper.playlistHeader(item).media.dynamic)
        assertTrue(mapper.playlistHeader(item.copy(isDynamic = true)).media.dynamic)
    }

    @Test fun `a playlist keeps each track's position, and a removal shifts the rest up`() = runTest {
        val source = FakeSource().apply {
            rows.value = row("library://playlist/9", "playlist", "Amsel Mix", subtitle = "Local files", editable = true)
            item = one("music_playlists_get")
            tracks = list("music_playlists_playlist_tracks")
        }
        val edits = MutableSharedFlow<PlaylistEdit>()
        val state =
            watch(PlaylistViewModel("library://playlist/9", source, mapper, playing, edits, messages, worker()).state)
        assertEquals(listOf(1, 2, 3, 4, 5), state.value.positions)
        // The server's word wins over the index: this playlist is not editable.
        assertFalse(state.value.playlist?.editable ?: true)
        assertEquals(3, state.value.positionAt(2))

        val third = state.value.tracks[2]
        edits.emit(PlaylistEdit.Removed("library://playlist/9", 2))
        assertEquals(4, state.value.songCount)
        assertEquals(third, state.value.tracks[1])
        assertEquals(listOf(1, 2, 3, 4), state.value.positions)

        // The server's task failed: the page reads the playlist again, the track back in its place.
        edits.emit(PlaylistEdit.Failed("library://playlist/9"))
        assertEquals(listOf(1, 2, 3, 4, 5), state.value.positions)
    }

    @Test fun `a refused removal whose reload fails shows the page's error, not the trimmed list`() = runTest {
        val source = FakeSource().apply {
            item = one("music_playlists_get")
            tracks = list("music_playlists_playlist_tracks")
        }
        val edits = MutableSharedFlow<PlaylistEdit>()
        val state =
            watch(PlaylistViewModel("library://playlist/9", source, mapper, playing, edits, messages, worker()).state)
        edits.emit(PlaylistEdit.Removed("library://playlist/9", 2))
        source.failure = MaConnectionException("offline")
        edits.emit(PlaylistEdit.Failed("library://playlist/9"))
        assertTrue(state.value.showUnavailable)
        assertTrue(state.value.tracks.isEmpty())
    }

    @Test fun `a long playlist shows its first rows at once, and counts once every page is in`() = runTest {
        val track = list("music_playlists_playlist_tracks").first()
        val source = FakeSource().apply {
            item = one("music_playlists_get")
            tracks = List(FIRST_ROWS + MaApi.PAGE + 1) { track.copy(uri = "library://track/$it", position = it + 1L) }
            held[FIRST_ROWS] = CompletableDeferred()
            held[FIRST_ROWS + MaApi.PAGE] = CompletableDeferred()
        }
        val state =
            watch(
                PlaylistViewModel(
                    "library://playlist/9",
                    source,
                    mapper,
                    playing,
                    emptyFlow(),
                    messages,
                    worker(),
                ).state,
            )
        assertEquals(FIRST_ROWS, state.value.tracks.size)
        assertFalse(state.value.complete)

        source.held.getValue(FIRST_ROWS).complete(Unit)
        assertEquals(FIRST_ROWS + MaApi.PAGE, state.value.tracks.size)
        assertFalse(state.value.complete)

        source.held.getValue(FIRST_ROWS + MaApi.PAGE).complete(Unit)
        assertEquals(FIRST_ROWS + MaApi.PAGE + 1, state.value.positions.last())
        assertTrue(state.value.complete)
    }

    @Test fun `a page that keeps its rows says why the rest failed, with Retry`() = runTest {
        val track = list("music_playlists_playlist_tracks").first()
        val source = FakeSource().apply {
            item = one("music_playlists_get")
            tracks = List(FIRST_ROWS + 1) { track.copy(uri = "library://track/$it", position = it + 1L) }
            held[FIRST_ROWS] = CompletableDeferred()
        }
        val state =
            watch(
                PlaylistViewModel(
                    "library://playlist/9",
                    source,
                    mapper,
                    playing,
                    emptyFlow(),
                    messages,
                    worker(),
                ).state,
            )
        source.failure = MaConnectionException("offline")
        source.held.getValue(FIRST_ROWS).complete(Unit)
        assertEquals(FIRST_ROWS, state.value.tracks.size)
        assertFalse(state.value.showUnavailable)
        val said = messages.pending.value.single()
        assertEquals(PlaybackR.string.error_not_connected, said.text)

        source.failure = null
        said.retry?.invoke()
        assertTrue(state.value.complete)
    }

    @Test fun `a playlist with no tracks is empty, not loading`() = runTest {
        val source = FakeSource().apply { item = one("music_playlists_get") }
        val state = watch(
            PlaylistViewModel("library://playlist/9", source, mapper, playing, emptyFlow(), messages, worker()).state,
        ).value
        assertTrue(state.isEmpty)
        assertFalse(state.tracksLoading)
    }
}
