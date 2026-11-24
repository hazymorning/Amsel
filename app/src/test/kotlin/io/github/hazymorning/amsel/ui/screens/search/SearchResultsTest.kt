package io.github.hazymorning.amsel.ui.screens.search

import io.github.hazymorning.amsel.api.ImageUrls
import io.github.hazymorning.amsel.api.ItemMapping
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.MediaItemImage
import io.github.hazymorning.amsel.api.MediaItemMetadata
import io.github.hazymorning.amsel.api.SearchResults
import io.github.hazymorning.amsel.api.ServerAddress
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultsTest {
    private val mapper = UiMapper { null }

    /** Builds art URLs, as once connected; [mapper] builds none. */
    private val drawn = UiMapper { ImageUrls(ServerAddress.parse("http://ma.local:8095") ?: error("address")) }
    private val covers = listOf("c1", "c2", "c3", "c4")

    private fun MediaItem.pictured(proxyId: String) =
        copy(metadata = MediaItemMetadata(images = listOf(MediaItemImage(proxyId = proxyId))))

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

    @Test fun anArtistWithoutAPictureWearsTheCoversTheIndexMadeIt() {
        val held = mapOf(
            "library://artist/1" to ArtRef("quarters 1", quarters = covers.map(::ArtRef)),
            "library://artist/2" to ArtRef("quarters 2", quarters = covers.map(::ArtRef)),
            "library://artist/3" to ArtRef("quarters 3"),
        )
        val raw = SearchResults(
            artists = listOf(
                item("artist", 1, "Aurora Tone"),
                item("artist", 2, "Aurora Borealis"),
                item("artist", 3, "Auroral").pictured("own"),
            ),
        )
        val results = searchResults("aurora tone", raw, drawn, held)
        assertEquals(held["library://artist/1"], (results.top as TopResult.Item).item.art)
        val (borealis, auroral) = results.artists
        assertEquals(held["library://artist/2"], borealis.art)
        assertEquals("$IMAGES/own", auroral.art?.base)
    }

    @Test fun aGuestTheIndexDoesNotHoldWearsTheCoversOfItsAlbumsAndSongsInTheAnswer() {
        val guest = ItemMapping("Mira Lune", "spotify://artist/7")
        fun by(type: String, n: Int, cover: String) =
            item(type, n, "$type $n", "spotify").copy(artists = listOf(guest)).pictured(cover)
        val raw = SearchResults(
            artists = listOf(MediaItem("7", "spotify", "Mira Lune", guest.uri, "artist")),
            albums = listOf(by("album", 1, "a1"), item("album", 2, "Not Hers").pictured("other")),
            tracks = listOf(by("track", 3, "t3"), by("track", 4, "a1"), by("track", 5, "t5"), by("track", 6, "t6")),
        )
        val top = (searchResults("mira lune", raw, drawn).top as TopResult.Item).item
        assertEquals(listOf("a1", "t3", "t5", "t6").map { "$IMAGES/$it" }, top.art?.quarters?.map { it.base })

        val listed = searchResults("mira", raw.copy(tracks = emptyList()), drawn).artists.single()
        assertEquals("$IMAGES/a1", listed.art?.base)
        assertEquals(emptyList<ArtRef>(), listed.art?.quarters)
    }

    @Test fun nothingFoundHasNoTopResult() {
        val results = searchResults("zzyzx", SearchResults(), mapper)
        assertNull(results.top)
        assertTrue(results.isEmpty)
        assertEquals("zzyzx", results.query)
    }

    private companion object {
        const val IMAGES = "http://ma.local:8095/imageproxy"
    }
}
