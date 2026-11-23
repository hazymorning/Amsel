package io.github.hazymorning.amsel.ui.screens.pages

import io.github.hazymorning.amsel.api.ImageUrls
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.MediaItemImage
import io.github.hazymorning.amsel.api.MediaItemMetadata
import io.github.hazymorning.amsel.api.ServerAddress
import io.github.hazymorning.amsel.app.MessageController
import io.github.hazymorning.amsel.data.CachedPage
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The artist page against the recorded 2.11 server shapes: what the index paints at once, and
 * what waits for the server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtistViewModelTest {
    private val messages = MessageController(MutableStateFlow(true))
    private val mapper = UiMapper { null }

    /** Draws covers, as the app does once connected. */
    private val drawn = UiMapper { ImageUrls(ServerAddress.parse("http://ma.local:8095") ?: error("address")) }
    private val playing = MutableStateFlow<String?>(null)

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    @Test fun `Retry on a page that was unavailable shows its skeleton again`() = runTest {
        val source = FakeSource().apply { failure = MaCommandException("music/item_by_uri", 2, "not found") }
        val vm = ArtistViewModel("library://artist/3", source, mapper, playing, messages)
        val state = watch(vm.state)
        assertTrue(state.value.showUnavailable)

        source.close()
        source.failure = null
        vm.retry()
        assertFalse(state.value.showUnavailable)
        assertTrue(state.value.contentLoading)
    }

    @Test fun `an artist shows five popular songs and every album, newest first`() = runTest {
        val source = FakeSource().apply {
            rows.value = row("library://artist/3", "artist", "Aurora Tone")
            item = one("music_artists_get")
            tracks = list("music_artists_artist_tracks").take(5)
            albums = list("music_artists_artist_albums")
        }
        val state = watch(ArtistViewModel("library://artist/3", source, mapper, playing, messages).state).value
        assertEquals("Aurora Tone", state.artist?.title)
        assertEquals(5, state.popular.size)
        assertEquals("First Light", state.subtitleOf(state.popular.first()))
        assertEquals(listOf("Second Wind", "First Light"), state.albums.map { it.title })
        assertFalse(state.showUnavailable)
    }

    /** Opened while Amsel connects, coming back to the front: the page fills in once it is connected. */
    @Test fun `an artist opened before the server could be reached loads once it is connected`() = runTest {
        val source = FakeSource().apply {
            failure = MaConnectionException("Not connected")
            item = one("music_artists_get")
            tracks = list("music_artists_artist_tracks").take(5)
            albums = list("music_artists_artist_albums")
        }
        val state = watch(ArtistViewModel(ARTIST, source, mapper, playing, messages).state)
        assertTrue(state.value.showUnavailable)

        source.failure = null
        source.connections.emit(Unit)
        assertFalse(state.value.showUnavailable)
        assertEquals("Aurora Tone", state.value.artist?.title)
        assertEquals(listOf("Second Wind", "First Light"), state.value.albums.map { it.title })
    }

    /** A server that answered, if only with a refusal, is not asked again by itself. */
    @Test fun `an artist the server refused waits for Retry`() = runTest {
        val source = FakeSource().apply { failure = MaCommandException("music/item_by_uri", 2, "not found") }
        val state = watch(ArtistViewModel(ARTIST, source, mapper, playing, messages).state)
        val asked = source.itemCalls
        source.connections.emit(Unit)
        assertEquals(asked, source.itemCalls)
        assertTrue(state.value.showUnavailable)
    }

    @Test fun `an artist the server cannot find is unavailable`() = runTest {
        val source = FakeSource().apply { failure = MaCommandException("music/item_by_uri", 2, "not found") }
        val state = watch(ArtistViewModel("library://artist/99", source, mapper, playing, messages).state)
        assertTrue(state.value.showUnavailable)
        // The page says it in its own place, not in a message too.
        assertTrue(messages.pending.value.isEmpty())
    }

    @Test fun `an artist the server does not describe in time gets its page from its albums and songs`() = runTest {
        val source = FakeSource().apply {
            itemFailure = MaConnectionException("No answer to music/item_by_uri")
            tracks = list("music_artists_artist_tracks").take(5)
            albums = list("music_artists_artist_albums")
        }
        val state = watch(ArtistViewModel(ARTIST, source, mapper, playing, messages).state)
        assertFalse(state.value.showUnavailable)
        assertEquals("Aurora Tone", state.value.artist?.title)
        assertEquals(listOf("Second Wind", "First Light"), state.value.albums.map { it.title })
        assertEquals(5, state.value.popular.size)
    }

    @Test fun `an artist paints its albums from the index before the server answers`() = runTest {
        val source = FakeSource(gated = true).apply {
            rows.value = row(ARTIST, "artist", "Aurora Tone")
            albumRows = listOf(
                row("library://album/4", "album", "First Light", artist = ARTIST, year = 2021),
                row("library://album/5", "album", "Second Wind", artist = ARTIST, year = 2023),
            )
        }
        val state = watch(ArtistViewModel(ARTIST, source, mapper, playing, messages).state).value
        assertEquals("Aurora Tone", state.artist?.title)
        assertEquals(
            listOf("Second Wind" to "2023", "First Light" to "2021"),
            state.albums.map {
                it.title to
                    it.subtitle
            },
        )
        assertFalse(state.contentLoading)
        assertTrue(state.popularPending)
    }

    @Test fun `albums do not wait for popular songs`() = runTest {
        val source = FakeSource(popularHeld = true).apply {
            item = one("music_artists_get")
            tracks = list("music_artists_artist_tracks").take(5)
            albums = list("music_artists_artist_albums")
        }
        val state = watch(ArtistViewModel(ARTIST, source, mapper, playing, messages).state)
        assertEquals(listOf("Second Wind", "First Light"), state.value.albums.map { it.title })
        assertEquals("Aurora Tone", state.value.artist?.title)
        assertTrue(state.value.popularPending)
        assertTrue(state.value.popular.isEmpty())

        source.popularGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(5, state.value.popular.size)
        assertFalse(state.value.popularPending)
        assertFalse(state.value.loading)
    }

    private fun artist(id: Int, name: String) = MediaItem("$id", "library", name, "library://artist/$id", "artist")

    /** The page's artist, without a picture of its own. */
    private val bare = MediaItem("3", "library", "Aurora Tone", ARTIST, "artist")

    private fun album(id: Int, year: Int?, cover: String, compilation: Boolean = false) = MediaItem(
        itemId = "$id",
        provider = "library",
        name = "Album $id",
        uri = "library://album/$id",
        mediaType = "album",
        metadata = MediaItemMetadata(images = listOf(MediaItemImage(proxyId = cover))),
        year = year,
        albumType = if (compilation) "compilation" else "album",
    )

    private fun page(source: FakeSource, similarOffered: Flow<Boolean> = flowOf(true)) =
        ArtistViewModel(ARTIST, source, mapper, playing, messages, similarOffered)

    @Test fun `albums do not wait for similar artists`() = runTest {
        val source = FakeSource(similarHeld = true).apply {
            item = one("music_artists_get")
            albums = list("music_artists_artist_albums")
            similar = listOf(artist(7, "Cirrus"), artist(8, "Nimbus"))
        }
        val state = watch(page(source).state)
        assertEquals(listOf("Second Wind", "First Light"), state.value.albums.map { it.title })
        assertFalse(state.value.loading)
        assertFalse(state.value.contentLoading)
        assertTrue(state.value.similar.isEmpty())

        source.similarGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("Cirrus", "Nimbus"), state.value.similar.map { it.title })
    }

    @Test fun `similar artists that fail or are none show no shelf and say nothing`() = runTest {
        val answers = listOf(
            MaCommandException("music/artists/similar_artists", 0, "no source"),
            MaConnectionException("No answer to music/artists/similar_artists"),
            null,
        )
        for (answer in answers) {
            val source = FakeSource().apply {
                item = one("music_artists_get")
                albums = list("music_artists_artist_albums")
                similarFailure = answer
            }
            val state = watch(page(source).state)
            assertEquals(1, source.similarCalls)
            assertTrue(state.value.similar.isEmpty())
            assertFalse(state.value.showUnavailable)
            assertFalse(state.value.loading)
            assertTrue(messages.pending.value.isEmpty())
        }
    }

    @Test fun `similar artists leave out the page's own artist and repeats`() = runTest {
        val own = one("music_artists_get")
        val held = own.providerMappings.first()
        val asItsServiceHoldsIt =
            MediaItem(held.itemId, held.providerInstance, own.name, "${held.providerInstance}://artist/x", "artist")
        val source = FakeSource().apply {
            item = own
            similar = listOf(artist(7, "Cirrus"), own, asItsServiceHoldsIt, artist(7, "Cirrus"), artist(8, "Nimbus"))
        }
        assertEquals(listOf("Cirrus", "Nimbus"), watch(page(source).state).value.similar.map { it.title })

        // The server did not give the artist: its uri alone tells it.
        source.item = null
        assertEquals(
            listOf("Cirrus", "Aurora Tone", "Nimbus"),
            watch(page(source).state).value.similar.map { it.title },
        )
    }

    @Test fun `similar artists are asked only where the server can find any`() = runTest {
        val source = FakeSource().apply {
            item = one("music_artists_get")
            similar = listOf(artist(7, "Cirrus"))
        }
        val offered = MutableStateFlow(false)
        val state = watch(page(source, offered).state)
        assertEquals(0, source.similarCalls)
        assertTrue(state.value.similar.isEmpty())
        // Right after a connect the server has not said yet what it has: the shelf comes when it does.
        offered.value = true
        assertEquals(listOf("Cirrus"), state.value.similar.map { it.title })
    }

    @Test fun `similar artists without a picture wear their albums' covers from the index`() = runTest {
        val source = FakeSource().apply {
            item = one("music_artists_get")
            similar = listOf(artist(7, "Cirrus"), artist(8, "Nimbus"))
            covers = mapOf("library://artist/7" to listOf("c1", "c2", "c3", "c4"))
        }
        val vm = ArtistViewModel(ARTIST, source, drawn, playing, messages, flowOf(true))
        val (cirrus, nimbus) = watch(vm.state).value.similar
        assertEquals(listOf("c1", "c2", "c3", "c4").map { "$IMAGES/$it" }, cirrus.art?.quarters?.map { it.base })
        assertNull(nimbus.art)
    }

    @Test fun `an artist with no picture, none in the index, wears its albums' covers, compilations last`() = runTest {
        val source = FakeSource().apply {
            item = bare
            albums = listOf(
                album(1, 2020, "c1"),
                album(2, 2024, "comp", compilation = true),
                album(3, 2023, "c3"),
                album(4, null, "c4"),
            )
        }
        val art = watch(ArtistViewModel(ARTIST, source, drawn, playing, messages).state).value.artist?.art
        assertEquals(listOf("c3", "c1", "c4", "comp").map { "$IMAGES/$it" }, art?.quarters?.map { it.base })
    }

    @Test fun `without albums it wears its popular songs' covers, and one cover alone`() = runTest {
        val songs = list("music_artists_artist_tracks").take(5)
        val source = FakeSource().apply {
            item = bare
            tracks = songs
        }
        val art = watch(ArtistViewModel(ARTIST, source, drawn, playing, messages).state).value.artist?.art
        assertEquals("$IMAGES/${songs.first().cover?.proxyId}", art?.base)
        assertEquals(emptyList<Any>(), art?.quarters)

        source.tracks = emptyList()
        source.albums = listOf(album(1, 2020, "c1"))
        val one = watch(ArtistViewModel(ARTIST, source, drawn, playing, messages).state).value.artist?.art
        assertEquals("$IMAGES/c1", one?.base)
        assertEquals(emptyList<Any>(), one?.quarters)
    }

    @Test fun `songs count with their album's picture, not their file's copy of it`() = runTest {
        val songs = list("music_artists_artist_tracks")
        val source = FakeSource().apply {
            item = bare
            tracks = songs
        }
        val art = watch(ArtistViewModel(ARTIST, source, drawn, playing, messages).state).value.artist?.art
        val albums = songs.mapNotNull { it.album?.cover?.proxyId }.map { "$IMAGES/$it" }.toSet()
        val shown = art?.quarters?.map { it.base }.orEmpty().ifEmpty { listOfNotNull(art?.base) }
        assertTrue(shown.isNotEmpty() && shown.all { it in albums })
    }

    @Test fun `its own picture, then the index's covers, come before the page's`() = runTest {
        val source = FakeSource().apply {
            item = one("music_artists_get")
            albums = listOf(album(1, 2020, "c1"))
        }
        val own = watch(ArtistViewModel(ARTIST, source, drawn, playing, messages).state).value.artist?.art
        assertEquals("$IMAGES/${one("music_artists_get").cover?.proxyId}", own?.base)

        source.item = bare
        source.rows.value = row(ARTIST, "artist", "Aurora Tone").copy(albumCovers = "i1\ni2")
        val held = watch(ArtistViewModel(ARTIST, source, drawn, playing, messages).state).value.artist?.art
        assertEquals("$IMAGES/i1", held?.base)
    }

    @Test fun `an artist opened again wears last time's covers before the server answers, and keeps them`() = runTest {
        val shelf = listOf(album(1, 2020, "c1"), album(2, 2021, "c2"))
        val source = FakeSource(gated = true).apply {
            shownPages[ARTIST] = CachedPage(bare, shelf = shelf)
            item = bare
            albums = shelf
        }
        val state = watch(ArtistViewModel(ARTIST, source, drawn, playing, messages).state)
        assertEquals("$IMAGES/c2", state.value.artist?.art?.base)

        source.open()
        advanceUntilIdle()
        assertFalse(state.value.loading)
        assertEquals("$IMAGES/c2", state.value.artist?.art?.base)
    }

    @Test fun `similar artists alone do not make a page`() {
        val similar = listOf(mapper.media(artist(7, "Cirrus")))
        val state = ArtistUiState(mapper.media(one("music_artists_get")), similar = similar)
        assertTrue(state.copy(unavailable = true).showUnavailable)
        assertTrue(state.copy(loading = true).contentLoading)
    }

    @Test fun `an artist the server cannot find drops the index's albums`() = runTest {
        val source = FakeSource().apply {
            rows.value = row(ARTIST, "artist", "Aurora Tone")
            albumRows = listOf(row("library://album/4", "album", "First Light", artist = ARTIST, year = 2021))
            failure = MaCommandException("music/item_by_uri", 2, "not found")
        }
        val state = watch(ArtistViewModel(ARTIST, source, mapper, playing, messages).state)
        assertTrue(state.value.showUnavailable)
        assertTrue(state.value.albums.isEmpty())
    }

    private companion object {
        const val IMAGES = "http://ma.local:8095/imageproxy"
    }
}
