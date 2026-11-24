package io.github.hazymorning.amsel.data

import androidx.room.Room
import io.github.hazymorning.amsel.api.FakeMaServer
import io.github.hazymorning.amsel.api.LibrarySort
import io.github.hazymorning.amsel.api.MaEvent
import io.github.hazymorning.amsel.api.MaJson
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.api.fixture
import io.github.hazymorning.amsel.data.library.INDEXED_TYPES
import io.github.hazymorning.amsel.data.library.LibraryDb
import io.github.hazymorning.amsel.data.library.LibraryRepository
import io.github.hazymorning.amsel.data.library.ProgressUpdate
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDb::class.java)
        .allowMainThreadQueries().build()
    private val info = MaJson.decodeFromJsonElement(
        ServerInfo.serializer(),
        fixture("2.10", "server_info")["response"]!!,
    )

    @Test fun `full sync fills every kind with ranks`() = withRecordedServer { api, _ ->
        val repo = LibraryRepository(db.dao(), api)
        repo.syncIfNeeded(info)
        val albums = repo.observe(MediaType.Album, LibrarySort.AToZ).first()
        assertTrue(albums.isNotEmpty())
        assertEquals(albums.map { it.sortName }.sorted(), albums.map { it.sortName })
        assertTrue(albums.all { it.subtitle != null })
        assertTrue(repo.observe(MediaType.Artist, LibrarySort.AToZ).first().isNotEmpty())
        assertTrue(repo.observe(MediaType.Playlist, LibrarySort.RecentlyAdded).first().isNotEmpty())
    }

    @Test fun `an item deleted while its kind's listing is on its way stays deleted`() {
        lateinit var repo: LibraryRepository
        var deleted = false
        val deleteDuringListing = { command: String, _: JsonObject? ->
            if (command == "music/albums/library_items" && !deleted) {
                deleted = true
                runBlocking { repo.onEvent(MaEvent("media_item_deleted", "library://album/4", null)) }
            }
            null
        }
        withRecordedServer(overrides = deleteDuringListing) { api, _ ->
            repo = LibraryRepository(db.dao(), api)
            repo.syncIfNeeded(info)
            val albums = repo.observe(MediaType.Album, LibrarySort.AToZ).first().map { it.uri }
            assertTrue(deleted)
            assertFalse("library://album/4" in albums)
        }
    }

    @Test fun `smart playlists and the user's own are listed apart`() = listOf("2.10", "2.11").forEach { v ->
        withRecordedServer(v) { api, _ ->
            db.clearAllTables()
            val repo = LibraryRepository(db.dao(), api)
            repo.syncIfNeeded(info)
            val smart = repo.smartPlaylists(limit = 100).map { it.name }
            val own = repo.ownPlaylists(limit = 100).map { it.name }
            assertEquals(8, smart.size)
            assertTrue("Recently added tracks" in smart)
            assertEquals(listOf("Amsel Mix"), own)
        }
    }

    @Test fun `radio stations are in the index`() = listOf("2.10", "2.11").forEach { v ->
        withRecordedServer(v) { api, _ ->
            db.clearAllTables()
            val repo = LibraryRepository(db.dao(), api)
            repo.syncIfNeeded(info)
            val radios = repo.observe(MediaType.Radio, LibrarySort.RecentlyAdded).first()
            assertEquals(listOf("Amsel Test Radio"), radios.map { it.name })
            assertEquals("radio", radios.single().mediaType)
        }
    }

    @Test fun `books and podcasts are indexed with their progress`() = listOf("2.10", "2.11").forEach { v ->
        val started = { command: String, _: JsonObject? ->
            if (command == "music/audiobooks/library_items") {
                listOf(FakeMaServer.recorded(v, "music_audiobooks_library_items__in_progress"))
            } else {
                null
            }
        }
        withRecordedServer(v, overrides = started) { api, _ ->
            db.clearAllTables()
            val repo = LibraryRepository(db.dao(), api)
            repo.syncIfNeeded(info)
            val books = repo.observe(MediaType.Audiobook, LibrarySort.RecentlyPlayed).first()
            // The book in progress leads: a book's last play moves only once it is finished.
            assertEquals(listOf("The Lantern Road", "Single File Tale"), books.map { it.name })
            val book = books.first()
            assertEquals("Ada Quill", book.subtitle)
            assertEquals(270, book.durationSec)
            assertEquals(false, book.fullyPlayed)
            assertTrue(book.resumeMs!! > 100_000)
            assertEquals(if (v == "2.11") "Ben Reader" else null, book.narrators)
            assertEquals(if (v == "2.11") "library://artist/1" else null, book.artistUri)
            assertEquals(null, books.last().resumeMs)
            val podcasts = repo.observe(MediaType.Podcast, LibrarySort.AToZ).first()
            assertEquals(listOf("Harbour Talk"), podcasts.map { it.name })
        }
    }

    @Test fun `only the signed-in person's progress reaches the index`() = withRecordedServer("2.11") { api, _ ->
        val repo = LibraryRepository(db.dao(), api, userId = { "me" })
        repo.syncIfNeeded(info)
        val uri = "library://audiobook/1"
        fun progress(user: String?, seconds: Int, played: Boolean, at: String = uri) = MaEvent(
            "playlog_updated",
            at,
            JsonObject(
                mapOf(
                    "uri" to JsonPrimitive(at),
                    "media_type" to JsonPrimitive("audiobook"),
                    "fully_played" to JsonPrimitive(played),
                    "seconds_played" to JsonPrimitive(seconds),
                    "userid" to (user?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull),
                ),
            ),
        )
        assertFalse(repo.onEvent(progress("someone else", 50, played = false)))
        assertEquals(null, repo.get(uri)!!.resumeMs)
        assertTrue(repo.onEvent(progress("me", 50, played = false)))
        assertEquals(50_000L, repo.get(uri)!!.resumeMs)
        // An Audiobookshelf sync names the provider's uri, and no user: every user's progress moved.
        val provider = repo.get(uri)!!.altUri ?: error("no provider uri")
        assertTrue(repo.onEvent(progress(null, 120, played = true, at = provider)))
        assertEquals(true, repo.get(uri)!!.fullyPlayed)
        repo.setPlayedLocally(uri, played = false, ProgressUpdate(uri, fullyPlayed = true, positionMs = 0))
        assertEquals(false, repo.get(uri)!!.fullyPlayed)
        assertEquals(0L, repo.get(uri)!!.resumeMs)
    }

    @Test fun `a refused mark puts back where the person stood, also for an episode the index lacks`() =
        withRecordedServer { api, _ ->
            val repo = LibraryRepository(db.dao(), api)
            val episode = "library://podcast_episode/7"
            val before = ProgressUpdate(episode, fullyPlayed = false, positionMs = 90_000)
            val seen = mutableListOf<ProgressUpdate>()
            coroutineScope {
                val watching = launch(Dispatchers.Unconfined) { repo.progress.collect { seen += it } }
                val undo = repo.setPlayedLocally(episode, played = true, before)
                undo()
                watching.cancel()
            }
            assertEquals(listOf(ProgressUpdate(episode, fullyPlayed = true, positionMs = 0), before), seen)
        }

    @Test fun `an artist's albums come from the index`() = withRecordedServer("2.11") { api, _ ->
        val repo = LibraryRepository(db.dao(), api)
        repo.syncIfNeeded(info)
        val album = repo.observe(MediaType.Album, LibrarySort.AToZ).first().first { it.name == "First Light" }
        val artist = album.artistUri ?: error("no artist")
        assertTrue(repo.albumsBy(artist).any { it.uri == album.uri })
        assertTrue(repo.albumsBy(artist).all { it.mediaType == "album" && it.artistUri == artist })
    }

    @Test fun `artists sync while a large album library is still loading`() {
        val albumsAsked = CountDownLatch(1)
        // No answer for albums: their sync waits while the other kinds go ahead.
        val holdAlbums = { command: String, _: JsonObject? ->
            if (command ==
                "music/albums/library_items"
            ) {
                emptyList<JsonObject>().also { albumsAsked.countDown() }
            } else {
                null
            }
        }
        withRecordedServer("2.11", overrides = holdAlbums) { api, _ ->
            val repo = LibraryRepository(db.dao(), api)
            coroutineScope {
                val sync = launch(Dispatchers.IO) { runCatching { repo.syncIfNeeded(info) } }
                val artists = withTimeout(5_000) {
                    repo.observe(MediaType.Artist, LibrarySort.AToZ).first { it.isNotEmpty() }
                }
                assertEquals(3, artists.size)
                assertEquals(0L, albumsAsked.count)
                assertTrue(repo.observe(MediaType.Album, LibrarySort.AToZ).first().isEmpty())
                assertTrue(repo.syncing.first())
                sync.cancel()
            }
        }
    }

    /** Answers `music/<type>/count` from [counts], keyed `albums` or `albums:favorites`. */
    private fun counted(counts: Map<String, Int>) = { command: String, args: JsonObject? ->
        val plural = command.removePrefix("music/").removeSuffix("/count").takeIf { command.endsWith("/count") }
        val key = if (args?.containsKey("favorite_only") == true) "$plural:favorites" else plural
        counts[key]?.let { listOf(FakeMaServer.result(JsonPrimitive(it))) }
    }

    private suspend fun indexCounts(): Map<String, Int> = INDEXED_TYPES.flatMap { type ->
        listOf(
            type.plural to db.dao().count(type.api),
            "${type.plural}:favorites" to db.dao().favoriteCount(type.api),
        )
    }.toMap()

    @Test fun `a second sync asks only the counts while they add up`() {
        val counts = mutableMapOf<String, Int>()
        withRecordedServer(overrides = counted(counts)) { api, fake ->
            val repo = LibraryRepository(db.dao(), api)
            repo.syncIfNeeded(info)
            counts += indexCounts()
            val calls = fake.received.size
            repo.syncIfNeeded(info)
            val asked = fake.received.drop(calls).map { it["command"].toString().trim('"') }
            assertTrue(asked.isNotEmpty() && asked.all { it.endsWith("/count") })
        }
    }

    @Test fun `a kind whose favorites changed meanwhile syncs again`() {
        val counts = mutableMapOf<String, Int>()
        withRecordedServer(overrides = counted(counts)) { api, fake ->
            val repo = LibraryRepository(db.dao(), api)
            repo.syncIfNeeded(info)
            counts += indexCounts()
            counts["albums:favorites"] = counts.getValue("albums:favorites") + 1
            val calls = fake.received.size
            repo.syncIfNeeded(info)
            val asked = fake.received.drop(calls).map { it["command"].toString().trim('"') }
            assertTrue("music/albums/library_items" in asked)
            assertFalse("music/artists/library_items" in asked)
        }
    }

    @Test fun `events add, favourite and delete items`() = withRecordedServer { api, _ ->
        val repo = LibraryRepository(db.dao(), api)
        repo.syncIfNeeded(info)
        val album = (
            fixture(
                "2.10",
                "music_albums_library_items",
            )["response"]!!.jsonObject["result"] as JsonArray
            ).first().jsonObject
        val renamed = JsonObject(album + ("name" to JsonPrimitive("Renamed")))
        val uri = (album["uri"] as JsonPrimitive).content
        repo.onEvent(MaEvent("media_item_updated", uri, renamed))
        assertEquals("Renamed", repo.get(uri)!!.name)
        repo.onEvent(
            MaEvent(
                "favorite_updated",
                uri,
                JsonObject(
                    mapOf(
                        "uri" to JsonPrimitive(uri),
                        "favorite" to JsonPrimitive(true),
                    ),
                ),
            ),
        )
        assertTrue(repo.get(uri)!!.favorite)
        // 2.11 item events carry favorite = null: the row keeps its state.
        repo.onEvent(MaEvent("media_item_updated", uri, JsonObject(renamed - "favorite")))
        assertTrue(repo.get(uri)!!.favorite)
        repo.onEvent(MaEvent("media_item_deleted", uri, null))
        assertFalse(repo.observe(MediaType.Album, LibrarySort.AToZ).first().any { it.uri == uri })
    }

    @Test fun `an event changes the index only where it holds something new`() = withRecordedServer { api, _ ->
        val repo = LibraryRepository(db.dao(), api)
        repo.syncIfNeeded(info)
        fun first(name: String) =
            (fixture("2.10", name)["response"]!!.jsonObject["result"] as JsonArray).first().jsonObject
        val album = first("music_albums_library_items")
        val uri = (album["uri"] as JsonPrimitive).content
        val renamed = JsonObject(album + ("name" to JsonPrimitive("Renamed")))
        assertTrue(repo.onEvent(MaEvent("media_item_updated", uri, renamed)))
        assertFalse(repo.onEvent(MaEvent("media_item_updated", uri, renamed)))
        // A song's guest artist joins the library too, but the index lists album artists only.
        val guest = JsonObject(
            first("music_artists_library_items") +
                ("item_id" to JsonPrimitive("999")) +
                ("uri" to JsonPrimitive("library://artist/999")),
        )
        assertFalse(repo.onEvent(MaEvent("media_item_added", "library://artist/999", guest)))
        assertNull(repo.get("library://artist/999"))
    }
}
