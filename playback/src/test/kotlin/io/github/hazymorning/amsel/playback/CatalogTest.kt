package io.github.hazymorning.amsel.playback

import android.provider.MediaStore
import androidx.media3.common.C
import io.github.hazymorning.amsel.api.ConnectionState
import io.github.hazymorning.amsel.api.FakeMaServer
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaClient
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.SearchResults
import io.github.hazymorning.amsel.api.fixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The car's reads of books and episodes, against the recorded 2.11 test library. */
class CatalogTest {
    private val book =
        fixture("2.11", "music_audiobooks_library_items__in_progress")
            .getValue("response").jsonObject.getValue("result").jsonArray[0]

    private fun answer(command: String): JsonObject = when (command) {
        "music/in_progress_items" -> FakeMaServer.recorded("2.11", "music_in_progress_items")

        "music/item_by_uri" -> FakeMaServer.result(book)

        "music/podcasts/podcast_episodes" -> FakeMaServer.recorded(
            "2.11",
            "music_podcasts_podcast_episodes__in_progress",
        )

        else -> FakeMaServer.result(null)
    }

    private fun withCatalog(block: suspend (DataCatalog) -> Unit) = runBlocking {
        val fake = FakeMaServer(fixture("2.11", "server_info").getValue("response")) { command, _ ->
            listOf(answer(command))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MaClient(OkHttpClient(), scope)
        try {
            client.start(fake.address, "token", fake.serverId)
            withTimeout(5_000) {
                client.state.filterIsInstance<ConnectionState.Connected>().first()
                block(DataCatalog(MaApi(client), wake = {}) { it == MediaType.Audiobook })
            }
        } finally {
            client.stop()
            scope.cancel()
            fake.close()
        }
    }

    @Test fun aBookInProgressComesWithHowFarItIs() = withCatalog { catalog ->
        val book = catalog.inProgress(10).single()
        assertEquals("library://audiobook/2", book.uri)
        assertEquals("audiobook", book.mediaType)
        assertFalse(book.browsable)
        assertEquals(102.0 / 270, requireNotNull(book.played), 0.001)
        assertFalse(book.finished)
    }

    @Test fun aPodcastOpensOnItsEpisodesNewestFirst() = withCatalog { catalog ->
        val episodes = catalog.children("library://podcast/1", 0, 50)
        assertEquals(listOf("Episode 3: Tides", "Episode 2: Tides", "Episode 1: Tides"), episodes.map { it.title })
        assertTrue(episodes.none { it.browsable })
        assertEquals(43.0 / 75, requireNotNull(episodes.first().played), 0.001)
        assertEquals("Harbour Talk", episodes.first().subtitle)
    }

    @Test fun onlyTheKindsTheIndexHoldsAreOffered() = withCatalog { catalog ->
        assertTrue(catalog.holds("audiobook"))
        assertFalse(catalog.holds("podcast"))
        assertFalse(catalog.holds("genre"))
    }

    @Test fun aSpokenNameFindsWhatIsNamedSoBeforeTheClosestSong() {
        fun item(name: String, type: String) = MediaItem(name, "library", name, "library://$type/$name", type)
        val results = SearchResults(
            artists = listOf(item("Queens of the Stone Age", "artist"), item("Queen", "artist")),
            tracks = listOf(item("Killer Queen", "track"), item("Queen", "track")),
        )
        assertEquals("library://artist/Queen", bestMatch("queen ", null, results)?.uri)
        assertEquals(
            "library://track/Queen",
            bestMatch("queen", MediaStore.Audio.Media.ENTRY_CONTENT_TYPE, results)?.uri,
        )
        assertEquals("library://track/Killer Queen", bestMatch("quen", null, results)?.uri)
        assertEquals(null, bestMatch("queen", MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE, results))
    }

    @Test fun aPlayStartsAtTheChosenItem() {
        val album = "library://album/1"
        val tracks = listOf("library://track/1", "library://track/2")
        assertEquals(
            PlayRequest("car", listOf(album), startItem = "library://track/2"),
            BrowseIds.toPlayRequest("car", tracks.map { BrowseIds.track(album, it) }, 1),
        )
        assertEquals(PlayRequest("car", listOf("library://track/2")), BrowseIds.toPlayRequest("car", tracks, 1))
        assertEquals(PlayRequest("car", tracks), BrowseIds.toPlayRequest("car", tracks, C.INDEX_UNSET))
        assertEquals(null, BrowseIds.toPlayRequest("car", listOf(""), 0))
    }
}
