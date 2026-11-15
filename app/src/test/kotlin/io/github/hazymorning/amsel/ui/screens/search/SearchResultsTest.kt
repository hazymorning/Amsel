package io.github.hazymorning.amsel.ui.screens.search

import io.github.hazymorning.amsel.api.ItemMapping
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.SearchResults
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultsTest {
    private val mapper = UiMapper { null }

    private fun item(type: String, n: Int, name: String, provider: String = "library", artist: String? = null) =
        MediaItem(
            itemId = "$n",
            provider = provider,
            name = name,
            uri = "$provider://$type/$n",
            mediaType = type,
            artists = listOfNotNull(artist?.let { ItemMapping(it, "library://artist/9") }),
        )

    private fun top(results: SearchResultsUi) = when (val t = results.top) {
        is TopResult.Song -> t.track.key
        is TopResult.Item -> t.item.key
        null -> null
    }

    @Test fun anExactNameWinsArtistsFirstIgnoringCase() {
        val raw = SearchResults(
            artists = listOf(item("artist", 1, "Bluebird", "spotify"), item("artist", 2, "Blue")),
            albums = listOf(item("album", 3, "BLUE")),
            tracks = listOf(item("track", 4, "blue")),
        )
        val results = searchResults("blue", raw, mapper)
        assertEquals("library://artist/2", top(results))
        assertEquals(listOf("spotify://artist/1"), results.artists.map { it.key })
        assertEquals(listOf("library://album/3"), results.albums.map { it.key })
    }

    @Test fun anExactPlaylistComesBeforeAnExactSong() {
        val raw = SearchResults(
            albums = listOf(item("album", 3, "Blue Light")),
            playlists = listOf(item("playlist", 5, "Blue ")),
            tracks = listOf(item("track", 4, "Blue")),
        )
        val results = searchResults("blue", raw, mapper)
        assertEquals("library://playlist/5", top(results))
        assertTrue(results.playlists.isEmpty())
        assertEquals(1, results.songs.size)
    }

    @Test fun anExactSongIsASongAndLeavesItsSection() {
        val raw = SearchResults(tracks = listOf(item("track", 1, "Into the Blue"), item("track", 2, "Blue Hour")))
        val results = searchResults("blue hour", raw, mapper)
        val song = results.top as TopResult.Song
        assertEquals("Blue Hour", song.track.title)
        assertEquals(listOf("Into the Blue"), results.songs.map { it.title })
    }

    @Test fun otherwiseTheFirstLibraryArtistThenAlbum() {
        val artists = listOf(item("artist", 1, "The Blue Rooms", "spotify"), item("artist", 2, "Blue Lagoon"))
        val albums = listOf(item("album", 3, "Deep Blue", "spotify"), item("album", 4, "Blue Light Sessions"))
        assertEquals("library://artist/2", top(searchResults("blu", SearchResults(artists, albums), mapper)))

        val results = searchResults("blu", SearchResults(artists.take(1), albums), mapper)
        val album = (results.top as TopResult.Item).item
        assertEquals("library://album/4", album.key)
        assertEquals(MediaKind.Album, album.kind)
        assertEquals(listOf("spotify://album/3"), results.albums.map { it.key })
    }

    @Test fun otherwiseTheFirstSongFromAnywhere() {
        val raw = SearchResults(
            artists = listOf(item("artist", 1, "The Blue Rooms", "spotify")),
            tracks = listOf(item("track", 2, "Bluebird", "spotify"), item("track", 3, "Blue Hour")),
        )
        val results = searchResults("blu", raw, mapper)
        assertEquals("spotify://track/2", top(results))
        assertEquals(listOf("library://track/3"), results.songs.map { it.key })
    }

    @Test fun radioStationsHaveTheirOwnSectionAndCanBeTheTopResult() {
        val raw = SearchResults(
            tracks = listOf(item("track", 1, "Paradise")),
            radio = listOf(item("radio", 5, "Radio Paradise"), item("radio", 6, "Paradise Lounge", "tunein")),
        )
        val results = searchResults("radio paradise", raw, mapper)
        val station = (results.top as TopResult.Item).item
        assertEquals(MediaKind.Radio, station.kind)
        assertEquals(listOf("tunein://radio/6"), results.radio.map { it.key })
        assertTrue(searchResults("lounge", SearchResults(radio = raw.radio), mapper).radio.isNotEmpty())
        assertTrue(!SearchResultsUi("x", radio = results.radio).isEmpty)
    }

    @Test fun booksAndPodcastsHaveTheirOwnSections() {
        val raw = SearchResults(
            tracks = listOf(item("track", 1, "Harbour Lights")),
            audiobooks = listOf(item("audiobook", 2, "The Harbour"), item("audiobook", 3, "Harbour Tales")),
            podcasts = listOf(item("podcast", 4, "Harbour Talk")),
        )
        val results = searchResults("the harbour", raw, mapper)
        val book = (results.top as TopResult.Item).item
        assertEquals(MediaKind.Audiobook, book.kind)
        assertEquals(listOf("library://audiobook/3"), results.audiobooks.map { it.key })
        assertEquals(listOf(MediaKind.Podcast), results.podcasts.map { it.kind })
        assertTrue(!SearchResultsUi("x", podcasts = results.podcasts).isEmpty)
    }

    @Test fun nothingFoundHasNoTopResult() {
        val results = searchResults("zzyzx", SearchResults(), mapper)
        assertNull(results.top)
        assertTrue(results.isEmpty)
        assertEquals("zzyzx", results.query)
    }
}
