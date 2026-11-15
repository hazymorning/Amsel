package io.github.hazymorning.amsel.ui.screens.pages

import io.github.hazymorning.amsel.api.ItemMapping
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.ui.components.lengthParts
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PagesUiStateTest {
    private fun track(n: Int, sec: Int = 200) = TrackUi("library://track/$n", "Song $n", "Artist", durationSec = sec)

    private val album = AlbumUi("library://album/1", "Album")
    private val playlist = PlaylistUi("library://playlist/1", "Playlist")

    @Test fun aFreshAlbumPageIsASkeleton() {
        val state = AlbumUiState()
        assertTrue(state.tracksLoading)
        assertFalse(state.showUnavailable)
    }

    @Test fun aCachedHeaderPaintsWhileTracksLoad() {
        val state = AlbumUiState(album = album, loading = true)
        assertTrue(state.tracksLoading)
        assertEquals(0, state.songCount)
    }

    @Test fun albumCountsAndLengthComeFromTheTracks() {
        val state = AlbumUiState(album = album, tracks = listOf(track(1, 200), track(2, 340)))
        assertFalse(state.tracksLoading)
        assertEquals(2, state.songCount)
        assertEquals(540, state.durationSec)
    }

    @Test fun albumRowsNameOnlyArtistsOtherThanTheAlbumArtist() {
        val state = AlbumUiState(album = album.copy(artist = "Artist"))
        assertNull(state.subtitleOf(track(1)))
        assertEquals("Guest", state.subtitleOf(track(2).copy(artist = "Guest")))
        // A compilation has no album artist: every row names its own.
        assertEquals("Artist", AlbumUiState(album = album).subtitleOf(track(3)))
    }

    @Test fun aCompilationIsWhatTheServerCallsOne() {
        val mapper = UiMapper { null }
        val album = MediaItem("4", "library", "Summer Hits", "library://album/4", "album")
        val various = album.copy(
            artists = listOf(ItemMapping("Various Artists", "library://artist/9")),
        )
        assertTrue(mapper.albumHeader(various.copy(albumType = "compilation")).compilation)
        assertFalse(mapper.albumHeader(various.copy(albumType = "album")).compilation)
        // One that names no artist at all lists them on every row too.
        assertTrue(mapper.albumHeader(album).compilation)
    }

    @Test fun popularSongsNameTheirAlbumOrForASingleTheirOtherArtists() {
        val state = ArtistUiState()
        assertEquals("Album", state.subtitleOf(track(1).copy(album = "Album")))
        assertEquals(track(2).artist, state.subtitleOf(track(2).copy(album = "Song 2")))
        assertEquals(track(3).artist, state.subtitleOf(track(3).copy(album = null)))
        val own = ArtistUiState(artist = MediaUi("library://artist/1", MediaKind.Artist, track(4).artist))
        assertNull(own.subtitleOf(track(4).copy(album = "Song 4")))
    }

    @Test fun unavailableOnlyWhenNothingIsCached() {
        assertTrue(AlbumUiState(unavailable = true).showUnavailable)
        assertFalse(AlbumUiState(album = album, tracks = listOf(track(1)), unavailable = true).showUnavailable)
    }

    @Test fun tracksWithoutTheirHeaderAreUnavailableToo() {
        assertTrue(AlbumUiState(tracks = listOf(track(1)), unavailable = true).showUnavailable)
        assertTrue(PlaylistUiState(tracks = listOf(track(1)), unavailable = true).showUnavailable)
        assertTrue(
            ArtistUiState(
                albums = listOf(MediaUi("library://album/1", MediaKind.Album, "A")),
                unavailable = true,
            ).showUnavailable,
        )
    }

    @Test fun playlistRowsKeepTheirKeysThroughARemoval() {
        val state =
            PlaylistUiState(playlist, tracks = listOf(track(1), track(2), track(1)), positions = listOf(1, 2, 3))
        assertEquals(3, state.rowKeys.toSet().size)
        assertEquals(listOf(state.rowKeys[0], state.rowKeys[2]), state.without(2).rowKeys)
    }

    @Test fun aLoadedPlaylistWithNoTracksIsEmpty() {
        assertTrue(PlaylistUiState(playlist = playlist).isEmpty)
        assertFalse(PlaylistUiState(playlist = playlist, loading = true).isEmpty)
        assertFalse(PlaylistUiState().isEmpty)
        assertFalse(PlaylistUiState(playlist = playlist, unavailable = true).isEmpty)
    }

    @Test fun anArtistWithAlbumsButNoPopularSongsIsNotUnavailable() {
        val state = ArtistUiState(
            artist = MediaUi("library://artist/1", MediaKind.Artist, "Artist"),
            albums = listOf(MediaUi("library://album/1", MediaKind.Album, "Album")),
            unavailable = true,
        )
        assertFalse(state.showUnavailable)
    }

    @Test fun lengthRoundsToTheNearestMinuteAndSplitsHours() {
        assertEquals(0 to 1, lengthParts(20))
        assertEquals(0 to 40, lengthParts(2402))
        assertEquals(1 to 0, lengthParts(3590))
        assertEquals(1 to 12, lengthParts(4321))
    }
}
