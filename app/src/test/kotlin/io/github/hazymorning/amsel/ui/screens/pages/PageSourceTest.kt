package io.github.hazymorning.amsel.ui.screens.pages

import io.github.hazymorning.amsel.api.ConnectionState
import io.github.hazymorning.amsel.api.FakeMaServer
import io.github.hazymorning.amsel.api.ItemMapping
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaClient
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.fixture
import io.github.hazymorning.amsel.data.PageMemory
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.TrackUi
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PageSourceTest {
    @Test fun `albums run newest first, undated last, each once and never the page's own`() {
        fun album(id: Int, year: Int?) =
            MediaItem("$id", "library", "Album $id", "library://album/$id", "album", year = year)
        val albums = listOf(album(1, 2019), album(2, null), album(3, 2023), album(4, 2019), album(3, 2023))
        val shown = UiMapper { null }.newestFirst(albums, except = "library://album/4")
        assertEquals(listOf("Album 3", "Album 1", "Album 2"), shown.map { it.title })
        assertEquals(listOf("2023", "2019", null), shown.map { it.subtitle })
    }

    @Test fun `removing an unknown position changes nothing`() {
        val state = PlaylistUiState(tracks = listOf(TrackUi("t", "T", "A")), positions = listOf(1))
        assertSame(state, state.without(7))
        assertEquals(2, PlaylistUiState().positionAt(1))
    }

    @Test fun `album and artist headers keep the album artist as the link`() {
        val item = MediaItem(
            "4",
            "library",
            "First Light",
            "library://album/4",
            "album",
            year = 2021,
            artists = listOf(
                ItemMapping("Aurora Tone", "library://artist/3"),
                ItemMapping("Cirrus", "library://artist/5"),
            ),
        )
        val header = UiMapper { null }.albumHeader(item)
        assertEquals("Aurora Tone, Cirrus", header.artist)
        assertEquals("library://artist/3", header.artistKey)
        assertEquals(2021, header.year)
    }

    @Test fun `the server source asks by id and music service, slashes and all`() = runBlocking {
        val fake = FakeMaServer(fixture("2.10", "server_info").getValue("response")) { command, _ ->
            when (command) {
                "auth" -> listOf(FakeMaServer.recorded("2.10", "auth"))
                "music/item_by_uri" -> listOf(FakeMaServer.recorded("2.10", "music_item_by_uri"))
                "music/albums/album_tracks" -> listOf(FakeMaServer.recorded("2.10", "music_albums_album_tracks"))
                else -> listOf(FakeMaServer.result(null))
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MaClient(OkHttpClient(), scope)
        try {
            client.start(fake.address, "t", fake.serverId)
            withTimeout(5000) { client.state.filterIsInstance<ConnectionState.Connected>().first() }
            // No server id: the memory keeps nothing.
            val memory = PageMemory(File(checkNotNull(System.getProperty("java.io.tmpdir")))) { null }
            val source = ServerPageSource(MaApi(client), { error("The library index is not needed here") }, memory)
            val key = "filesystem_local--x://album/Aurora Tone/First Light (2021)"
            assertEquals("library://album/4", source.item(key)?.uri)
            assertEquals(3, source.albumTracks(key).size)
            val args = fake.received.first { it.command == "music/albums/album_tracks" }.getValue("args") as JsonObject
            assertEquals("Aurora Tone/First Light (2021)", args.getValue("item_id").jsonPrimitive.content)
            assertEquals("filesystem_local--x", args.getValue("provider_instance_id_or_domain").jsonPrimitive.content)
        } finally {
            client.stop()
            scope.cancel()
            fake.close()
        }
    }

    private val JsonObject.command: String get() = getValue("command").jsonPrimitive.content
}
