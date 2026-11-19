package io.github.hazymorning.amsel.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End to end against a real Music Assistant (scripts/test-server, with the fake speakers
 * "Kitchen" and "Living Room"). Skipped unless MA_URL is set; CI runs it on 2.10 and 2.11.
 */
class LiveServerTest {
    @Test fun `library, pages and search`() = live { api, _ ->
        LibrarySort.entries.forEach { sort ->
            assertEquals("albums $sort", 4, api.libraryItems(MediaType.Album, sort).size)
        }
        assertEquals(3, api.allLibraryItems(MediaType.Artist).size)
        assertTrue(api.allLibraryItems(MediaType.Playlist).any { it.name == "Amsel Mix" })
        val album = api.libraryItems(MediaType.Album).first()
        assertTrue(api.albumTracks(album.itemId, album.provider).isNotEmpty())
        val artist = api.libraryItems(MediaType.Artist).first()
        assertTrue(api.artistAlbums(artist.itemId, artist.provider).isNotEmpty())
        assertTrue(api.artistTopTracks(artist.itemId, artist.provider).isNotEmpty())
        val playlists = api.allLibraryItems(MediaType.Playlist)
        val mix = playlists.first { it.name == "Amsel Mix" }
        assertTrue(api.playlistTracks(mix.itemId, mix.provider).items.isNotEmpty())
        // MA's own smart playlists are told apart from the library's playlist.
        assertEquals(8, playlists.count { it.isSmartPlaylist })
        assertTrue(!mix.isSmartPlaylist)
        // The test library has no radio stations; the listing answers, sorted, in both versions.
        LibrarySort.entries.forEach { sort ->
            assertTrue(api.libraryItems(MediaType.Radio, sort).all { it.mediaType == "radio" })
        }
        val results = api.search(album.name)
        assertTrue(results.albums.first().isLibraryItem)
        assertTrue(Images(ServerAddress.parse(liveUrl!!)!!).url(album.cover) != null)
    }

    @Test fun `recently played can be asked of one speaker`() = live { api, _ ->
        val kitchen = waitForSpeakers(api).first { it.shownName == "Kitchen" }
        assertTrue(api.recentlyPlayed(13, queueId = "amsel-no-such-speaker").isEmpty())
        api.recentlyPlayed(13, queueId = kitchen.playerId)
    }

    @Test fun `play an album on a speaker, move it, change the volume`() = live { api, _ ->
        val speakers = waitForSpeakers(api)
        val kitchen = speakers.first { it.shownName == "Kitchen" }
        val living = speakers.first { it.shownName == "Living Room" }
        val album = api.libraryItems(MediaType.Album).first()

        api.playMedia(kitchen.playerId, listOf(album.uri), QueueOption.Replace)
        val playing =
            waitFor {
                api.queues().firstOrNull { it.queueId == kitchen.playerId }?.takeIf {
                    it.state == "playing" &&
                        it.currentItem != null
                }
            }
        assertTrue(playing.items > 0)

        api.transfer(kitchen.playerId, living.playerId)
        val moved = waitFor {
            api.queues().firstOrNull { it.queueId == living.playerId }?.takeIf {
                it.items > 0 &&
                    it.currentItem != null
            }
        }
        assertEquals(playing.items, moved.items)

        api.setVolume(living.playerId, 25)
        waitFor { api.players().firstOrNull { it.playerId == living.playerId }?.takeIf { it.volumeLevel == 25 } }
        // Louder takes the server's step: its setting, or its ladder's 2 between 10 and 30.
        val louder = 25 + (api.volumeStep() ?: 2)
        api.volumeUp(living.playerId)
        waitFor { api.players().firstOrNull { it.playerId == living.playerId }?.takeIf { it.volumeLevel == louder } }
        api.pause(living.playerId)
    }

    @Test fun `a group is let go through its leader, which plays on`() = live { api, _ ->
        val speakers = waitForSpeakers(api)
        val kitchen = speakers.first { it.shownName == "Kitchen" }.playerId
        val living = speakers.first { it.shownName == "Living Room" }.playerId
        try {
            api.playMedia(kitchen, listOf(api.libraryItems(MediaType.Album).first().uri))
            api.setRepeat(kitchen, RepeatMode.All)
            waitFor { api.queue(kitchen)?.takeIf { it.isPlaying } }
            api.setMembers(kitchen, add = listOf(living), remove = emptyList())
            waitFor { api.groups().takeIf { it == mapOf(kitchen to setOf(living)) } }

            // As Ungroup sends it, whichever speaker is in use: leader and members from the server's word.
            api.groups().forEach { (leader, members) -> api.setMembers(leader, emptyList(), members.toList()) }
            waitFor { api.players().takeIf { all -> all.none { it.syncedTo != null || it.groupMembers.isNotEmpty() } } }
            // Not for a moment only: the leader's stream is its own again and goes on.
            delay(2_000)
            assertTrue(checkNotNull(api.queue(kitchen)).isPlaying)
        } finally {
            api.setRepeat(kitchen, RepeatMode.Off)
            api.clearQueue(kitchen)
            api.groups().forEach { (leader, members) -> api.setMembers(leader, emptyList(), members.toList()) }
        }
    }

    @Test fun `speakers named one by one after they joined are all let go, each once it is named`() = live { api, _ ->
        val speakers = waitForSpeakers(api).map { it.playerId }
        val leader = speakers.first()
        val members = speakers.drop(1)
        suspend fun named() = api.groups()[leader].orEmpty()
        try {
            api.setMembers(leader, add = members, remove = emptyList())
            // The first sign of the group, which is when Ungroup shows: seconds before all are named.
            waitFor { named().takeIf { it.isNotEmpty() } }
            val gone = mutableSetOf<String>()
            var quiet = 0
            while (quiet < 10) {
                val late = named() - gone
                if (late.isEmpty()) {
                    quiet++
                    delay(500)
                } else {
                    quiet = 0
                    gone += late
                    api.setMembers(leader, emptyList(), late.toList())
                }
            }
            assertEquals(members.toSet(), gone)
            assertTrue(api.players().none { it.syncedTo != null || it.groupMembers.isNotEmpty() })
        } finally {
            api.setMembers(leader, emptyList(), members)
        }
    }

    @Test fun `a speaker to let go that plays along with nobody is passed over, a leader out of reach refuses`() =
        live { api, _ ->
            val speakers = waitForSpeakers(api)
            val kitchen = speakers.first { it.shownName == "Kitchen" }.playerId
            val living = speakers.first { it.shownName == "Living Room" }.playerId
            api.setMembers(kitchen, emptyList(), listOf(living, "no-such-speaker"))
            assertTrue(api.groups().isEmpty())

            val refused = assertThrows(MaCommandException::class.java) {
                runBlocking { api.setMembers("no-such-speaker", emptyList(), listOf(living)) }
            }
            assertTrue(refused.isSpeakerUnresponsive)
        }

    @Test fun `a group player made in Music Assistant is no group to let go, at rest or playing`() = live { api, _ ->
        val speakers = waitForSpeakers(api)
        val members = listOf("Kitchen", "Living Room").map { name -> speakers.first { it.shownName == name }.playerId }
        val created = api.client.call(
            "players/create_group_player",
            buildJsonObject {
                put("provider", "sync_group")
                put("name", "Downstairs")
                put("members", JsonArray(members.map(::JsonPrimitive)))
                put("dynamic", false)
            },
        )
        val group = checkNotNull(created.decodeOrNull(Player.serializer())).playerId
        try {
            // At rest it lists its speakers, and they stay on their own.
            waitFor { api.players().firstOrNull { it.playerId == group }?.takeIf { it.groupMembers.size == 2 } }
            assertTrue(api.groups().isEmpty())
            assertTrue(api.players().none { it.activeGroup != null })

            api.playMedia(group, listOf(api.libraryItems(MediaType.Album).first().uri))
            // While it plays, one of its speakers leads the other in step, and it runs them both.
            val playing = waitFor { api.players().takeIf { all -> all.any { it.syncedTo != null } } }
            assertEquals(members.toSet(), playing.filter { it.activeGroup == group }.map { it.playerId }.toSet())
            assertTrue(playing.associateBy { it.playerId }.syncGroups().isEmpty())
        } finally {
            api.stop(group)
            api.client.call(
                "players/cmd/power",
                buildJsonObject {
                    put("player_id", group)
                    put("powered", false)
                },
            )
            api.client.call("players/remove_group_player", buildJsonObject { put("player_id", group) })
        }
    }

    @Test fun `an endless mix of an album replaces what plays and goes on without repeat`() = live { api, _ ->
        val kitchen = waitForSpeakers(api).first { it.shownName == "Kitchen" }.playerId
        val albums = api.libraryItems(MediaType.Album)
        try {
            api.playMedia(kitchen, listOf(albums.first().uri))
            api.setRepeat(kitchen, RepeatMode.All)

            api.playEndlessMix(kitchen, albums.last().uri)
            val mix = waitFor { api.queue(kitchen)?.takeIf { it.isDynamic && it.isPlaying } }
            assertEquals(albums.last().name, mix.endlessMixOf)
            assertEquals(RepeatMode.Off, mix.repeatMode)
            assertTrue(mix.shuffleEnabled)

            // Play next and Add to Up next go to the server as ever; in a mix it places them and stays one.
            val songs = api.albumTracks(albums.first().itemId, albums.first().provider).map { it.uri }
            api.playMedia(kitchen, songs.take(1), QueueOption.Next)
            waitFor { api.queued(kitchen).takeIf { songs.first() in it } }
            api.playMedia(kitchen, listOf(albums.first().uri), QueueOption.Add)
            // The album stays a source only until its songs are all in Up next, which is at once
            // unless they were played lately: never seen on a fresh server.
            waitFor {
                api.queue(kitchen)?.takeIf { it.isDynamic && it.isPlaying }?.takeIf { now ->
                    now.sources.size == 2 || api.queued(kitchen).containsAll(songs)
                }
            }

            // A second mix finds the first holding the queue and still starts.
            api.playEndlessMix(kitchen, albums.first().uri)
            waitFor { api.queue(kitchen)?.takeIf { it.endlessMixOf == albums.first().name } }

            api.playMedia(kitchen, listOf(albums.first().uri))
            val plain = waitFor { api.queue(kitchen)?.takeIf { !it.isDynamic } }
            assertEquals(null, plain.endlessMixOf)
        } finally {
            api.clearQueue(kitchen)
        }
    }

    @Test fun `a mix of nothing leaves the speaker with what it had`() = live { api, _ ->
        val kitchen = waitForSpeakers(api).first { it.shownName == "Kitchen" }.playerId
        val empty = api.allLibraryItems(MediaType.Playlist).firstOrNull {
            !it.isPluginSmartPlaylist && api.playlistTracks(it.itemId, it.provider).items.isEmpty()
        }
        assumeTrue("every playlist of this server holds songs", empty != null)
        try {
            api.playMedia(kitchen, listOf(api.libraryItems(MediaType.Album).first().uri))
            val before = waitFor { api.queue(kitchen)?.takeIf { it.isPlaying && it.items > 0 } }

            assertThrows(NothingToMixException::class.java) { runBlocking { api.playEndlessMix(kitchen, empty!!.uri) } }
            val after = api.queue(kitchen)!!
            assertEquals(before.items, after.items)
            assertTrue(!after.isDynamic)
        } finally {
            api.clearQueue(kitchen)
        }
    }

    @Test fun `a music service's similar songs are its own, and they play`() = live { api, _ ->
        val kitchen = waitForSpeakers(api).first { it.shownName == "Kitchen" }.playerId
        val seed = checkNotNull(api.itemByUri("test://track/0_0_0"))
        val songs = api.similarTracks(seed.itemId, seed.provider)
        assertEquals(SIMILAR_ITEMS, songs.size)
        assertTrue(songs.all { it.provider == "test" && it.mediaType == "track" })
        assertEquals(songs, (songs + seed).withoutSeed(seed))
        val two = songs.take(2).map { it.uri }
        try {
            api.playMedia(kitchen, two)
            waitFor { api.queue(kitchen)?.takeIf { it.isPlaying && it.currentItem != null } }
            assertEquals(two, api.queued(kitchen))
        } finally {
            api.clearQueue(kitchen)
        }
    }

    @Test fun `the library's artists and songs answer a list of similar ones`() = live { api, _ ->
        val artist = api.libraryItems(MediaType.Artist).first()
        assertTrue(api.similarArtists(artist.itemId, artist.provider).all { it.mediaType == "artist" })
        val album = api.libraryItems(MediaType.Album).first()
        val song = api.albumTracks(album.itemId, album.provider).first()
        assertTrue(api.similarTracks(song.itemId, song.provider).all { it.mediaType == "track" })
    }

    @Test fun `similar ones of something the library does not hold are an error`() = live { api, _ ->
        fun refused(ask: suspend () -> Unit) = assertThrows(MaCommandException::class.java) { runBlocking { ask() } }
        assertTrue(refused { api.similarTracks("999999", "library") }.isNotFound)
        assertTrue(refused { api.similarArtists("999999", "library") }.isNotFound)
    }

    private suspend fun MaApi.queued(queueId: String): List<String?> =
        queueItems(queueId, 0).items.map { it.mediaItem?.uri }

    private suspend fun MaApi.groups(): Map<String, Set<String>> = players().associateBy { it.playerId }.syncGroups()
}
