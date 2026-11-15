package io.github.hazymorning.amsel.api

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaApiTest {
    @Test fun `2_10 sorts with order_by`() = withFakeApi("2.10", { _, _ ->
        listOf(FakeMaServer.recorded("2.10", "music_albums_library_items"))
    }) { api, fake ->
        val albums = api.libraryItems(MediaType.Album, LibrarySort.RecentlyAdded)
        assertTrue(albums.isNotEmpty())
        val args = fake.args("music/albums/library_items")
        assertEquals("timestamp_added_desc", args["order_by"]!!.jsonPrimitive.content)
        assertFalse(args.containsKey("sort_field"))
    }

    @Test fun `2_11 sorts with sort_field`() = withFakeApi("2.11", { _, _ ->
        listOf(FakeMaServer.recorded("2.11", "music_albums_library_items"))
    }) { api, fake ->
        api.libraryItems(MediaType.Album, LibrarySort.AToZ)
        val args = fake.args("music/albums/library_items")
        assertEquals("sort_name", args["sort_field"]!!.jsonPrimitive.content)
        assertEquals("asc", args["sort_direction"]!!.jsonPrimitive.content)
    }

    @Test fun `artists listing asks for album artists only`() = withFakeApi("2.10", { _, _ ->
        listOf(FakeMaServer.recorded("2.10", "music_artists_library_items"))
    }) { api, fake ->
        api.libraryItems(MediaType.Artist)
        assertEquals("true", fake.args("music/artists/library_items")["album_artists_only"]!!.jsonPrimitive.content)
    }

    @Test fun `an item that does not decode does not end a paged listing`() = withFakeApi("2.11", { _, args ->
        fun album(n: Int) = MaJson.parseToJsonElement(
            """{"item_id":"$n","provider":"library","name":"A$n","uri":"library://album/$n","media_type":"album"}""",
        )
        val first = args!!["offset"]!!.jsonPrimitive.int == 0
        val page = if (first) {
            List(500) {
                if (it ==
                    0
                ) {
                    JsonPrimitive("not an item")
                } else {
                    album(it)
                }
            }
        } else {
            listOf(album(500))
        }
        listOf(FakeMaServer.result(JsonArray(page)))
    }) { api, _ ->
        assertEquals(500, api.allLibraryItems(MediaType.Album).size)
    }

    @Test fun `2_11 favourites use set_item`() = withFakeApi("2.11", { _, _ ->
        listOf(FakeMaServer.result(null))
    }) { api, fake ->
        api.setFavorite(ItemUri("library", "album", "1"), true)
        assertEquals("library://album/1", fake.args("music/favorites/set_item")["item"]!!.jsonPrimitive.content)
    }

    @Test fun `2_10 favourites use add_item`() = withFakeApi("2.10", { _, _ ->
        listOf(FakeMaServer.result(null))
    }) { api, fake ->
        api.setFavorite(ItemUri("library", "album", "1"), true)
        assertEquals("library://album/1", fake.args("music/favorites/add_item")["item"]!!.jsonPrimitive.content)
    }

    @Test fun `search puts library matches first`() = withFakeApi("2.10", { _, _ ->
        val a = MaJson.parseToJsonElement(
            """{"item_id":"x","provider":"spotify","name":"Remote","uri":"spotify://track/x","media_type":"track"}""",
        )
        val b = MaJson.parseToJsonElement(
            """{"item_id":"1","provider":"library","name":"Local","uri":"library://track/1","media_type":"track"}""",
        )
        listOf(FakeMaServer.result(JsonObject(mapOf("tracks" to JsonArray(listOf(a, b))))))
    }) { api, _ ->
        assertEquals(listOf("Local", "Remote"), api.search("x").tracks.map { it.name })
    }

    @Test fun `search asks for radio stations too`() = withFakeApi("2.11", { _, _ ->
        val station = MaJson.parseToJsonElement(
            """{"item_id":"5","provider":"library","name":"Radio Paradise","uri":"library://radio/5","media_type":"radio"}""",
        )
        listOf(FakeMaServer.result(JsonObject(mapOf("radio" to JsonArray(listOf(station))))))
    }) { api, fake ->
        assertEquals(listOf("Radio Paradise"), api.search("paradise").radio.map { it.name })
        val types = fake.args("music/search")["media_types"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("radio" in types)
    }

    @Test fun `2_10 playlist tracks arrive as joined partials`() = withFakeApi("2.10", { _, _ ->
        val items = fixture("2.10", "music_playlists_playlist_tracks")["response"]!!.jsonObject["result"] as JsonArray
        listOf(
            FakeMaServer.result(JsonArray(items.take(1)), partial = true),
            FakeMaServer.result(JsonArray(items.drop(1))),
        )
    }) { api, _ ->
        val expected = (
            fixture(
                "2.10",
                "music_playlists_playlist_tracks",
            )["response"]!!.jsonObject["result"] as JsonArray
            ).size
        val page = api.playlistTracks("1", "library")
        assertEquals(expected, page.items.size)
        assertTrue(page.complete)
    }

    @Test fun `play media sends uris and option`() = withFakeApi("2.10", { _, _ ->
        listOf(FakeMaServer.result(null))
    }) { api, fake ->
        api.playMedia("kitchen", listOf("library://album/4"), QueueOption.Replace, shuffle = true)
        val args = fake.args("player_queues/play_media")
        assertEquals("replace", args["option"]!!.jsonPrimitive.content)
        assertEquals("true", args["shuffle"]!!.jsonPrimitive.content)
    }

    @Test fun `play in order says so, or the queue's shuffle would apply`() = withFakeApi("2.10", { _, _ ->
        listOf(FakeMaServer.result(null))
    }) { api, fake ->
        api.playMedia("kitchen", listOf("library://playlist/2"), QueueOption.Replace)
        assertEquals("false", fake.args("player_queues/play_media")["shuffle"]!!.jsonPrimitive.content)
    }

    @Test fun `2_11 asks popular songs and the stand-in at once, five of each`() = withFakeApi("2.11", { command, _ ->
        when (command) {
            "music/artists/top_tracks" -> listOf(FakeMaServer.result(JsonArray(emptyList())))
            else -> listOf(FakeMaServer.recorded("2.11", "music_artists_artist_tracks"))
        }
    }) { api, fake ->
        val popular = api.artistTopTracks("3", "library")
        assertEquals(5, popular.size)
        assertEquals("5", fake.args("music/artists/top_tracks")["limit"]!!.jsonPrimitive.content)
        assertEquals("5", fake.args("music/artists/artist_tracks")["limit"]!!.jsonPrimitive.content)
    }

    @Test fun `2_11 the server's ranking wins over the stand-in`() = withFakeApi("2.11", { command, _ ->
        val tracks = fixture("2.11", "music_artists_artist_tracks")["response"]!!.jsonObject["result"] as JsonArray
        when (command) {
            "music/artists/top_tracks" -> listOf(FakeMaServer.result(JsonArray(tracks.takeLast(2))))
            else -> listOf(FakeMaServer.result(JsonArray(tracks.take(5))))
        }
    }) { api, _ ->
        val tracks = fixture("2.11", "music_artists_artist_tracks")["response"]!!.jsonObject["result"] as JsonArray
        val expected = tracks.takeLast(2).map { it.jsonObject["uri"]!!.jsonPrimitive.content }
        assertEquals(expected, api.artistTopTracks("3", "library").map { it.uri })
    }

    @Test fun `2_10 asks for the stand-in only when the server has no ranking`() = withFakeApi("2.10", { command, _ ->
        when (command) {
            "music/artists/top_tracks" -> listOf(FakeMaServer.result(JsonArray(emptyList())))
            else -> listOf(FakeMaServer.recorded("2.10", "music_artists_artist_tracks"))
        }
    }) { api, fake ->
        assertEquals(5, api.artistTopTracks("2", "library").size)
        // 2.10 has no limit on artist listings: none is sent, the page takes five.
        assertFalse(fake.args("music/artists/top_tracks").containsKey("limit"))
        assertFalse(fake.args("music/artists/artist_tracks").containsKey("limit"))
    }

    @Test fun `2_10 a ranked artist never asks for every track`() = withFakeApi("2.10", { command, _ ->
        when (command) {
            "music/artists/top_tracks" -> listOf(FakeMaServer.recorded("2.10", "music_artists_artist_tracks"))
            else -> listOf(FakeMaServer.result(JsonArray(emptyList())))
        }
    }) { api, fake ->
        assertEquals(5, api.artistTopTracks("2", "library").size)
        assertTrue(fake.received.none { it["command"]!!.jsonPrimitive.content == "music/artists/artist_tracks" })
    }

    @Test fun `a music service error falls back to the artist's own songs`() = withFakeApi("2.11", { command, _ ->
        when (command) {
            "music/artists/top_tracks" -> listOf(FakeMaServer.error(999, "Spotify is rate limited"))
            else -> listOf(FakeMaServer.recorded("2.11", "music_artists_artist_tracks"))
        }
    }) { api, _ ->
        assertEquals(5, api.artistTopTracks("3", "library").size)
    }

    @Test fun `radio stations list like the library, in the chosen order`() = listOf("2.10", "2.11").forEach { v ->
        withFakeApi(v, { _, _ ->
            listOf(FakeMaServer.recorded(v, "music_radios_library_items__favorites"))
        }) { api, fake ->
            val radios = api.libraryItems(MediaType.Radio, LibrarySort.RecentlyAdded)
            assertEquals(listOf("radio"), radios.map { it.mediaType })
            val args = fake.args("music/radios/library_items")
            if (v == "2.10") {
                assertEquals("timestamp_added_desc", args["order_by"]!!.jsonPrimitive.content)
            } else {
                assertEquals("timestamp_added", args["sort_field"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test fun `a radio station plays by its uri`() = withFakeApi("2.11", { _, _ ->
        listOf(FakeMaServer.result(null))
    }) { api, fake ->
        api.playMedia("kitchen", listOf("library://radio/1"))
        val args = fake.args("player_queues/play_media")
        assertEquals("library://radio/1", (args["media"] as JsonArray).single().jsonPrimitive.content)
        assertEquals("replace", args["option"]!!.jsonPrimitive.content)
    }

    @Test fun `artists you play most sort by play count, played ones only`() = listOf("2.10", "2.11").forEach { v ->
        withFakeApi(v, { _, _ -> listOf(FakeMaServer.recorded(v, "music_artists_library_items")) }) { api, fake ->
            assertTrue(api.mostPlayed(MediaType.Artist, limit = 13).isNotEmpty())
            val args = fake.args("music/artists/library_items")
            assertEquals("13", args["limit"]!!.jsonPrimitive.content)
            assertEquals("true", args["played_only"]!!.jsonPrimitive.content)
            assertEquals("true", args["album_artists_only"]!!.jsonPrimitive.content)
            if (v == "2.10") {
                assertEquals("play_count_desc", args["order_by"]!!.jsonPrimitive.content)
                assertFalse(args.containsKey("sort_field"))
            } else {
                assertEquals("play_count", args["sort_field"]!!.jsonPrimitive.content)
                assertEquals("desc", args["sort_direction"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test fun `recently played asks for the kinds Home shows`() = withFakeApi("2.11", { _, _ ->
        listOf(FakeMaServer.recorded("2.11", "music_recently_played_items"))
    }) { api, fake ->
        assertEquals(listOf("library://playlist/9"), api.recentlyPlayed(13).map { it.uri })
        val args = fake.args("music/recently_played_items")
        val types = (args["media_types"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(listOf("album", "playlist", "artist", "radio"), types)
        assertEquals("true", args["user_initiated_only"]!!.jsonPrimitive.content)
        assertFalse(args.containsKey("queue_id"))
    }

    @Test fun `recently played on one speaker names its queue`() = withFakeApi("2.11", { _, _ ->
        listOf(FakeMaServer.recorded("2.11", "music_recently_played_items"))
    }) { api, fake ->
        api.recentlyPlayed(13, queueId = "kitchen")
        assertEquals("kitchen", fake.args("music/recently_played_items")["queue_id"]!!.jsonPrimitive.content)
    }

    @Test fun `an item uri splits at its first slash, so provider ids keep theirs`() {
        assertEquals(ItemUri("library", "album", "12"), ItemUri.parse("library://album/12"))
        assertEquals(
            ItemUri("filesystem_local--x", "track", "Artist/Album/01 - Song.flac"),
            ItemUri.parse("filesystem_local--x://track/Artist/Album/01 - Song.flac"),
        )
        assertEquals("library://album/12", ItemUri("library", "album", "12").toString())
        assertNull(ItemUri.parse("album/12"))
        assertNull(ItemUri.parse("library://album"))
    }
}
