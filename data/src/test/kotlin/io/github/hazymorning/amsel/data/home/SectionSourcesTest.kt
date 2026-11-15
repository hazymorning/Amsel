package io.github.hazymorning.amsel.data.home

import androidx.room.Room
import io.github.hazymorning.amsel.api.FakeMaServer
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaJson
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.api.fixture
import io.github.hazymorning.amsel.data.library.LibraryDb
import io.github.hazymorning.amsel.data.library.LibraryRepository
import io.github.hazymorning.amsel.data.withRecordedServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SectionSourcesTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDb::class.java)
        .allowMainThreadQueries().build()
    private val info = MaJson.decodeFromJsonElement(
        ServerInfo.serializer(),
        fixture("2.11", "server_info")["response"]!!,
    )

    /** The 2.11 test library, synced into the index, with [overrides] for single commands. */
    private fun withLibrary(
        overrides: (String, JsonObject?) -> List<JsonObject>? = { _, _ -> null },
        speaker: String? = null,
        block: suspend (SectionSources, FakeMaServer) -> Unit,
    ) = withRecordedServer("2.11", overrides) { api: MaApi, fake ->
        val library = LibraryRepository(db.dao(), api)
        library.syncIfNeeded(info)
        block(SectionSources(api, { library }, { speaker }), fake)
    }

    private suspend fun SectionSources.names(option: SectionOption, limit: Int = 13) =
        load(option, limit, emptyList(), null).items.map { it.name }

    @Test fun smartPlaylistsAreMusicAssistantsOwnFromTheIndex() = withLibrary { sources, _ ->
        val smart = sources.names(SectionOption.SmartPlaylists)
        assertEquals(8, smart.size)
        assertTrue("Amsel Mix" !in smart)
        assertEquals(listOf("Amsel Mix"), sources.names(SectionOption.YourPlaylists))
        assertEquals(3, sources.names(SectionOption.SmartPlaylists, limit = 3).size)
    }

    @Test fun smartPlaylistsPreferTheSmartPlaylistsPlugin() = withLibrary(
        overrides = { command, _ ->
            if (command != "music/playlists/library_items") {
                null
            } else {
                val recorded = FakeMaServer.recorded("2.11", "music_playlists_library_items")
                val items = JsonArray(recorded["result"]!!.jsonArray + pluginPlaylist("11", "Rediscover"))
                listOf(JsonObject(recorded + ("result" to items)))
            }
        },
    ) { sources, _ ->
        assertEquals(listOf("Rediscover"), sources.names(SectionOption.SmartPlaylists))
        assertTrue("Rediscover" !in sources.names(SectionOption.YourPlaylists))
    }

    @Test fun continueListeningLooksUpWhereThePersonStands() {
        val book = (
            fixture("2.11", "music_audiobooks_library_items__in_progress")["response"]!!
                .jsonObject["result"] as JsonArray
            ).first().jsonObject
        val episode = fixture("2.11", "music_item_by_uri__episode")["response"]!!.jsonObject["result"]!!.jsonObject
        val started = JsonArray(
            listOf(
                fixture("2.11", "music_in_progress_items")["response"]!!.jsonObject["result"]!!.jsonArray.single(),
                fixture("2.11", "music_in_progress_items__episode")["response"]!!.jsonObject["result"]!!.jsonArray
                    .single(),
            ),
        )
        withLibrary(
            overrides = { command, args ->
                when (command) {
                    "music/in_progress_items" -> listOf(FakeMaServer.result(started))

                    "music/item_by_uri" -> {
                        val uri = args!!["uri"]!!.jsonPrimitive.content
                        listOf(FakeMaServer.result(if (uri.startsWith("library://")) book else episode))
                    }

                    else -> null
                }
            },
        ) { sources, _ ->
            val items = sources.load(SectionOption.ContinueListening, 13, emptyList(), null).items
            assertEquals(listOf("The Lantern Road", "Episode 3: Tides"), items.map { it.name })
            val lantern = items.first()
            assertEquals("Ada Quill", lantern.subtitle)
            assertEquals(270, lantern.durationSec)
            assertTrue(lantern.resumeMs!! > 100_000)
            val tides = items.last()
            assertEquals("Harbour Talk", tides.subtitle)
            assertTrue(tides.parentUri!!.endsWith("://podcast/Harbour Talk"))
        }
    }

    @Test fun aBookThatCannotBeLookedUpKeepsItsRow() = withLibrary(
        overrides = { command, _ ->
            when (command) {
                "music/in_progress_items" -> listOf(FakeMaServer.recorded("2.11", "music_in_progress_items"))
                "music/item_by_uri" -> listOf(FakeMaServer.error(2, "Not found"))
                else -> null
            }
        },
    ) { sources, _ ->
        val item = sources.load(SectionOption.ContinueListening, 13, emptyList(), null).items.single()
        assertEquals("The Lantern Road", item.name)
        assertEquals("Ada Quill", item.subtitle)
    }

    @Test fun newEpisodesComeFromTheServersRowAndBooksAndPodcastsFromTheIndex() = withLibrary(
        overrides = { command, _ ->
            if (command == "music/recommendations/items") {
                listOf(FakeMaServer.recorded("2.11", "music_podcasts_podcast_episodes__page"))
            } else {
                null
            }
        },
    ) { sources, _ ->
        assertEquals(listOf("Episode 3: Tides", "Episode 2: Tides"), sources.names(SectionOption.NewEpisodes))
        assertEquals(setOf("The Lantern Road", "Single File Tale"), sources.names(SectionOption.NewAudiobooks).toSet())
        assertEquals(listOf("Harbour Talk"), sources.names(SectionOption.YourPodcasts))
        assertFalse(SectionOption.ContinueListening.readsIndex)
        assertTrue(SectionOption.NewAudiobooks.readsIndex)
    }

    private fun pluginPlaylist(id: String, name: String) = buildJsonObject {
        put("item_id", id)
        put("provider", "library")
        put("name", name)
        put("uri", "library://playlist/$id")
        put("media_type", "playlist")
        put("owner", "Smart Playlist")
        put("is_editable", true)
        putJsonArray("provider_mappings") {
            add(
                buildJsonObject {
                    put("item_id", "sp_$id")
                    put("provider_domain", "smart_playlist")
                    put("provider_instance", "smart_playlist--x1")
                },
            )
        }
    }

    @Test fun albumsAndStationsComeFromTheIndex() = withLibrary { sources, _ ->
        val newest = sources.load(SectionOption.NewAlbums, 13, emptyList(), null).items
        assertEquals(4, newest.size)
        assertTrue(newest.all { it.mediaType == "album" && it.subtitle != null && it.image?.proxyId != null })
        assertEquals(listOf("Amsel Test Radio"), sources.names(SectionOption.AllStations))
    }

    @Test fun recentlyPlayedTakesTheIndexRowForArtAndOwner() = withLibrary { sources, fake ->
        val recent = sources.load(SectionOption.Everything, 13, emptyList(), null)
        assertFalse(recent.fallback)
        val mix = recent.items.single()
        assertEquals("library://playlist/9", mix.uri)
        assertEquals("Local files", mix.subtitle)
        val types = (fake.args("music/recently_played_items")["media_types"] as JsonArray).map {
            it.jsonPrimitive.content
        }
        assertEquals(listOf("album", "playlist", "artist", "radio"), types)
    }

    @Test fun nothingPlayedYetShowsTheLibrarysAlbums() = withLibrary({ command, _ ->
        if (command == "music/recently_played_items") listOf(FakeMaServer.result(JsonArray(emptyList()))) else null
    }) { sources, _ ->
        val albums = sources.load(SectionOption.Everything, 13, emptyList(), null)
        assertTrue(albums.fallback)
        assertEquals(listOf("First Light", "High Clouds", "Low End", "Second Wind"), albums.items.map { it.name })
    }

    @Test fun playedHereIsWhatLastPlayedOnTheSpeakerInUse() = withLibrary(speaker = "kitchen") { sources, fake ->
        val here = sources.load(SectionOption.PlayedHere, 13, emptyList(), null)
        assertEquals("kitchen", fake.args("music/recently_played_items")["queue_id"]!!.jsonPrimitive.content)
        assertEquals("kitchen", here.speaker)
        assertEquals(listOf("library://playlist/9"), here.items.map { it.uri })
    }

    @Test fun aSpeakerNothingPlayedOnShowsTheWholeHistory() = withLibrary(
        overrides = { command, args ->
            val onOne = command == "music/recently_played_items" && args?.containsKey("queue_id") == true
            if (onOne) listOf(FakeMaServer.result(JsonArray(emptyList()))) else null
        },
        speaker = "kitchen",
    ) { sources, _ ->
        val here = sources.load(SectionOption.PlayedHere, 13, emptyList(), null)
        assertEquals(null, here.speaker)
        assertEquals(listOf("library://playlist/9"), here.items.map { it.uri })
    }

    @Test fun recentlyPlayedAlbumsAskTheServerForPlayedOnes() = withLibrary { sources, fake ->
        sources.load(SectionOption.RecentAlbums, 13, emptyList(), null)
        val args = fake.args("music/albums/library_items")
        assertEquals("true", args["played_only"]!!.jsonPrimitive.content)
        assertEquals("last_played", args["sort_field"]!!.jsonPrimitive.content)
        assertEquals("13", args["limit"]!!.jsonPrimitive.content)
    }

    @Test fun artistsYouPlayMostSortByPlayCount() = withLibrary { sources, fake ->
        assertTrue(sources.names(SectionOption.TopArtists).isNotEmpty())
        assertEquals("play_count", fake.args("music/artists/library_items")["sort_field"]!!.jsonPrimitive.content)
    }

    @Test fun anArtistWithoutAPictureWearsItsAlbumsCoversFromTheIndexInEveryArtistsSection() = withLibrary(
        overrides = { command, _ ->
            if (command != "music/artists/library_items") {
                null
            } else {
                val recorded = FakeMaServer.recorded("2.11", "music_artists_library_items")
                val artists = recorded.getValue("result").jsonArray.map { artist ->
                    val aurora = artist.jsonObject
                    if (aurora.getValue("uri").jsonPrimitive.content != AURORA) return@map artist
                    // The recorded artists all have a picture: this one is a favourite without one.
                    val metadata = JsonObject(aurora.getValue("metadata").jsonObject - "images")
                    JsonObject(aurora + ("metadata" to metadata) + ("favorite" to JsonPrimitive(true)))
                }
                listOf(JsonObject(recorded + ("result" to JsonArray(artists))))
            }
        },
    ) { sources, _ ->
        val covers = db.dao().get(AURORA)?.albumCovers?.lines().orEmpty()
        assertEquals(2, covers.size)
        // From the index, and the server's lists, whose items take the index's row.
        for (option in listOf(SectionOption.FavoriteArtists, SectionOption.TopArtists, SectionOption.RecentArtists)) {
            val aurora = sources.load(option, 13, emptyList(), null).items.single { it.uri == AURORA }
            assertEquals(option.name, covers, aurora.covers)
        }
        val pinned = sources.load(SectionOption.Pinned, 13, listOf(AURORA), null).items.single()
        assertEquals(covers, pinned.covers)
    }

    @Test fun randomAlbumsGoOnFromTheOnesHomeShows() = withLibrary { sources, _ ->
        val shown = sources.load(SectionOption.RandomAlbums, 2, emptyList(), null)
        val all = sources.load(SectionOption.RandomAlbums, 13, emptyList(), shown).items
        assertEquals(shown.items, all.take(2))
        assertEquals(4, all.distinctBy { it.uri }.size)
    }

    @Test fun randomAlbumsAreTheLibrarysAlbumsInAnotherOrder() = withLibrary { sources, _ ->
        val random = sources.names(SectionOption.RandomAlbums)
        assertEquals(listOf("First Light", "High Clouds", "Low End", "Second Wind"), random.sorted())
    }

    @Test fun favouritesFilterTheIndex() = withLibrary { sources, _ ->
        val favourites = sources.load(SectionOption.FavoriteAlbums, 13, emptyList(), null).items
        assertTrue(favourites.all { it.favorite })
    }

    @Test fun pinsResolveFromTheIndexThenTheServerAndKeepWhatNeitherKnows() = withLibrary({ command, args ->
        if (command != "music/item_by_uri") return@withLibrary null
        if (args?.get("uri")?.jsonPrimitive?.content == "spotify://album/x") {
            val item = buildJsonObject {
                put("item_id", "x")
                put("provider", "spotify")
                put("name", "From Spotify")
                put("uri", "spotify://album/x")
                put("media_type", "album")
            }
            listOf(FakeMaServer.result(item))
        } else {
            listOf(FakeMaServer.error(4, "not found"))
        }
    }) { sources, _ ->
        val offline = HomeItem("tidal://album/y", "album", "Kept While Offline")
        val pins = listOf("library://radio/1", "spotify://album/x", "tidal://album/y", "tidal://album/gone")
        val items = sources.load(SectionOption.Pinned, 13, pins, SectionItems(listOf(offline))).items
        assertEquals(listOf("Amsel Test Radio", "From Spotify", "Kept While Offline"), items.map { it.name })
        assertEquals("radio", items.first().mediaType)
    }

    private companion object {
        const val AURORA = "library://artist/3"
    }
}
