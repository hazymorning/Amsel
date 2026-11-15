package io.github.hazymorning.amsel.data

import androidx.room.Room
import io.github.hazymorning.amsel.api.FakeMaServer
import io.github.hazymorning.amsel.api.MaEvent
import io.github.hazymorning.amsel.api.MaJson
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.api.fixture
import io.github.hazymorning.amsel.data.library.LibraryDb
import io.github.hazymorning.amsel.data.library.LibraryRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * An artist without a picture wears its albums' covers through what changes the index: syncs, and
 * events about it and its albums. The recorded artists all have a picture, so Aurora Tone loses its.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryCoversTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDb::class.java)
        .allowMainThreadQueries().build()
    private val info =
        MaJson.decodeFromJsonElement(ServerInfo.serializer(), fixture("2.11", "server_info").getValue("response"))
    private val albumListing = listing("music_albums_library_items")
    private val artistListing = listing("music_artists_library_items")
    private val aurora = artistListing.first { it.uri == AURORA }.withoutPicture()

    @After fun close() = db.close()

    @Test fun `a sync gives an artist without a picture its albums' covers, newest first`() = synced { repo ->
        assertEquals(listOf(repo.cover(SECOND_WIND), repo.cover(FIRST_LIGHT)), repo.covers(AURORA))
    }

    @Test fun `an update that changes nothing the row holds leaves the row alone`() = synced { repo ->
        val before = repo.get(AURORA)
        assertFalse(repo.onEvent(updated(aurora)))
        assertEquals(before, repo.get(AURORA))
    }

    @Test fun `a renamed or liked artist keeps its albums' covers`() = synced { repo ->
        val covers = repo.covers(AURORA)
        assertTrue(repo.onEvent(updated(aurora.with("name", JsonPrimitive("Aurora")))))
        assertEquals("Aurora", repo.get(AURORA)?.name)
        assertEquals(covers, repo.covers(AURORA))
        // 2.10 tells of a like this way.
        assertTrue(repo.onEvent(updated(aurora.with("favorite", JsonPrimitive(true)))))
        assertEquals(true, repo.get(AURORA)?.favorite)
        assertEquals(covers, repo.covers(AURORA))
    }

    @Test fun `an artist's new picture replaces the covers, which come back once it is gone`() = synced { repo ->
        val covers = repo.covers(AURORA)
        assertTrue(repo.onEvent(updated(artistListing.first { it.uri == AURORA })))
        assertNotNull(repo.get(AURORA)?.imageProxyId)
        assertNull(repo.get(AURORA)?.albumCovers)
        assertTrue(repo.onEvent(updated(aurora)))
        assertEquals(covers, repo.covers(AURORA))
    }

    @Test fun `a new album puts its cover first, and a deleted one takes its cover away`() = synced { repo ->
        val firstLight = repo.cover(FIRST_LIGHT)
        val newest = albumListing.first { it.uri == SECOND_WIND }
            .with("item_id", JsonPrimitive("9"))
            .with("uri", JsonPrimitive("library://album/9"))
            .with("year", JsonPrimitive(2025))
            .with("metadata", picture("newest"))
        assertTrue(repo.onEvent(MaEvent("media_item_added", "library://album/9", newest)))
        assertEquals(listOf("newest", repo.cover(SECOND_WIND), firstLight), repo.covers(AURORA))
        assertTrue(repo.onEvent(MaEvent("media_item_deleted", SECOND_WIND, null)))
        assertEquals(listOf("newest", firstLight), repo.covers(AURORA))
    }

    /** Syncs the recorded 2.11 library with these [albums] and [artists], then runs [block]. */
    private fun synced(
        albums: List<JsonObject> = albumListing,
        artists: List<JsonObject> = artistListing.map { if (it.uri == AURORA) aurora else it },
        block: suspend (LibraryRepository) -> Unit,
    ) {
        val lists = mapOf("music/albums/library_items" to albums, "music/artists/library_items" to artists)
        val answer = { command: String, _: JsonObject? ->
            lists[command]?.let { listOf(FakeMaServer.result(JsonArray(it))) }
        }
        withRecordedServer("2.11", overrides = answer) { api, _ ->
            val repo = LibraryRepository(db.dao(), api)
            repo.syncIfNeeded(info)
            block(repo)
        }
    }

    private suspend fun LibraryRepository.covers(uri: String) = get(uri)?.albumCovers?.lines().orEmpty()

    private suspend fun LibraryRepository.cover(uri: String) = get(uri)?.imageProxyId ?: error("no cover for $uri")

    private fun updated(item: JsonObject) = MaEvent("media_item_updated", item.uri, item)

    private fun listing(name: String) =
        fixture("2.11", name).getValue("response").jsonObject.getValue("result").jsonArray.map { it.jsonObject }

    private val JsonObject.uri: String get() = getValue("uri").jsonPrimitive.content

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    private fun JsonObject.withoutPicture() = with("metadata", JsonObject(getValue("metadata").jsonObject - "images"))

    private fun picture(proxyId: String) = buildJsonObject {
        putJsonArray("images") {
            add(
                buildJsonObject {
                    put("type", "thumb")
                    put("proxy_id", proxyId)
                },
            )
        }
    }

    private companion object {
        const val AURORA = "library://artist/3"
        const val SECOND_WIND = "library://album/3"
        const val FIRST_LIGHT = "library://album/4"
    }
}
