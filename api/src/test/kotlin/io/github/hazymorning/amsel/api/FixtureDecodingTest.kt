package io.github.hazymorning.amsel.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every model decodes the real server output of both supported releases. */
class FixtureDecodingTest {
    private val versions = listOf("2.10", "2.11")

    @Test fun `server info`() = versions.forEach { v ->
        val info = MaJson.decodeFromJsonElement(ServerInfo.serializer(), fixture(v, "server_info")["response"]!!)
        assertTrue("$v schema", Schema.isSupported(info.schemaVersion))
    }

    @Test fun players() = versions.forEach { v ->
        val players = decodeList(v, "players_all", Player.serializer())
        assertTrue(players.isNotEmpty())
        assertTrue(players.all { it.shownName.isNotBlank() })
        assertTrue(players.any { it.isShownSpeaker(null) })
        // On their own the recorded speakers are at 40, and their group volume reads 0.
        assertTrue(players.all { it.volume == 40 && it.hasVolume })
        val playing = decodeOne(v, "players_get__playing", Player.serializer())
        assertTrue(playing.isPlaying)
        assertNotNull(playing.currentMedia?.title)
    }

    @Test fun `a group's volume is its members'`() {
        val group =
            Player("g", type = "group", volumeControl = "native", groupVolume = 30, groupMembers = listOf("a", "b"))
        assertEquals(30, group.volume)
        assertTrue(group.hasVolume)
    }

    @Test fun queues() = versions.forEach { v ->
        val queue = decodeOne(v, "player_queues_get__playing", PlayerQueue.serializer())
        assertEquals("playing", queue.state)
        assertNotNull(queue.currentItem?.mediaItem)
        val items = decodeList(v, "player_queues_items", QueueItem.serializer())
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.mediaItem != null })
    }

    @Test fun `library listings`() = versions.forEach { v ->
        listOf(
            "music_albums_library_items",
            "music_artists_library_items",
            "music_playlists_library_items",
            "music_tracks_library_items",
        ).forEach { name ->
            val items = decodeList(v, name, MediaItem.serializer())
            assertTrue("$v $name", items.isNotEmpty())
        }
        val albums = decodeList(v, "music_albums_library_items", MediaItem.serializer())
        assertTrue("$v albums have covers", albums.any { it.cover?.proxyId != null })
        assertTrue("$v albums have artists", albums.all { it.artists.isNotEmpty() })
    }

    @Test fun `pages and sub-listings`() = versions.forEach { v ->
        assertEquals("album", decodeOne(v, "music_albums_get", MediaItem.serializer()).mediaType)
        val tracks = decodeList(v, "music_albums_album_tracks", MediaItem.serializer())
        assertTrue(tracks.all { it.duration != null && it.trackNumber != null })
        assertTrue(decodeList(v, "music_artists_artist_albums", MediaItem.serializer()).isNotEmpty())
        val playlistTracks = decodeList(v, "music_playlists_playlist_tracks", MediaItem.serializer())
        assertTrue(playlistTracks.isNotEmpty())
        assertTrue(playlistTracks.all { it.position != null })
    }

    @Test fun search() = versions.forEach { v ->
        val results = decodeOne(v, "music_search", SearchResults.serializer())
        assertTrue(results.tracks.isNotEmpty() || results.albums.isNotEmpty())
    }

    @Test fun `recently played`() = versions.forEach { v ->
        decodeList(v, "music_recently_played_items", MediaItem.serializer())
    }

    @Test fun `radio stations`() = versions.forEach { v ->
        assertTrue("$v empty", decodeList(v, "music_radios_library_items__empty", MediaItem.serializer()).isEmpty())
        val station = decodeList(v, "music_radios_library_items", MediaItem.serializer()).single()
        assertEquals("radio", station.mediaType)
        assertTrue("$v library uri", station.uri.startsWith("library://radio/"))
        assertNotNull("$v artwork", station.cover?.proxyId)
        assertTrue(station.providerMappings.any { it.providerDomain == BUILTIN_PROVIDER })
        val favourite = decodeList(v, "music_radios_library_items__favorites", MediaItem.serializer()).single()
        assertEquals(true, favourite.favorite)
        // Playing it: the queue's item is the station, with no duration, and the speaker says so.
        val queue = decodeOne(v, "player_queues_get__radio", PlayerQueue.serializer())
        assertEquals("radio", queue.currentItem?.mediaItem?.mediaType)
        assertEquals(null, queue.currentItem?.duration)
        assertEquals("radio", decodeOne(v, "players_get__radio", Player.serializer()).currentMedia?.mediaType)
    }

    @Test fun `smart playlists are told apart from the user's`() = versions.forEach { v ->
        val playlists = decodeList(v, "music_playlists_library_items", MediaItem.serializer())
        val (smart, own) = playlists.partition { it.isSmartPlaylist }
        assertEquals("$v smart", 8, smart.size)
        assertTrue(smart.all { it.owner == "Music Assistant" && !it.isEditable })
        assertEquals(listOf("Amsel Mix"), own.map { it.name })
        // A playlist made in Music Assistant comes from the same provider, but is the user's own.
        val made = smart.first().copy(isEditable = true, owner = "Amsel Test")
        assertFalse(made.isSmartPlaylist)
    }
}
