package io.github.hazymorning.amsel.ui.screens.pages

import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.ProviderMapping
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

// Fixtures are recorded 2.11 server replies.
@OptIn(ExperimentalCoroutinesApi::class)
class SimilarSongsViewModelTest {
    private val mapper = UiMapper { null }
    private val playing = MutableStateFlow<String?>(null)
    private val served = list("music_tracks_similar_tracks")

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    private fun page(source: FakePageSource, key: String = SONG) = SimilarSongsViewModel(key, source, mapper, playing)

    @Test fun `similar songs list in the server's order, each with its artist and album`() = runTest {
        val source = FakePageSource(similarHeld = true).apply { similar = served }
        val state = watch(page(source).state)
        assertTrue(state.value.loading)
        assertTrue(state.value.songs.isEmpty())

        source.similarGate.complete(Unit)
        advanceUntilIdle()
        assertFalse(state.value.loading)
        assertEquals(served.map { it.uri }, state.value.songs.map { it.key })
        assertEquals(served.first().artists.first().name, state.value.songs.first().artist)
        assertEquals(served.first().album?.name, state.value.songs.first().album)

        playing.value = served[1].uri
        assertEquals(served[1].uri, state.value.currentTrackKey)
    }

    @Test fun `no similar songs is an empty page with nothing to retry`() = runTest {
        val state = watch(page(FakePageSource()).state)
        assertTrue(state.value.songs.isEmpty())
        assertFalse(state.value.loading)
        assertNull(state.value.failure)
    }

    @Test fun `a song no music service knows is an empty page, not a failure`() = runTest {
        val source = FakePageSource().apply {
            similar = served
            similarFailure = MaCommandException("music/tracks/similar_tracks", 0, "No Music Provider found")
        }
        val state = watch(page(source).state)
        assertTrue(state.value.songs.isEmpty())
        assertFalse(state.value.loading)
        assertNull(state.value.failure)
    }

    @Test fun `a lost connection says so, and Retry asks again`() = runTest {
        val source = FakePageSource().apply {
            similar = served
            similarFailure = MaConnectionException("No answer to music/tracks/similar_tracks")
        }
        val vm = page(source)
        val state = watch(vm.state)
        assertEquals(PlaybackR.string.error_not_connected, state.value.failure)
        assertTrue(state.value.songs.isEmpty())
        assertFalse(state.value.loading)

        source.similarFailure = null
        vm.retry()
        assertNull(state.value.failure)
        assertEquals(served.size, state.value.songs.size)
    }

    @Test fun `the song itself and repeats are left out`() = runTest {
        val seed = MediaItem(
            "7",
            "library",
            "Seed",
            SONG,
            "track",
            providerMappings = listOf(ProviderMapping(served[1].itemId, "test")),
        )
        val source = FakePageSource().apply {
            item = seed
            similar = listOf(served[0], seed, served[1], served[0], served[2])
        }
        val others = listOf(served[0].uri, served[2].uri)
        assertEquals(others, watch(page(source).state).value.songs.map { it.key })

        // Without the server's song, or after it failed, only the URI identifies it.
        source.item = null
        source.similar = listOf(served[0], served[1], served[0], served[2])
        assertEquals(others, watch(page(source, served[1].uri).state).value.songs.map { it.key })
        source.failure = MaCommandException("music/item_by_uri", 2, "not found")
        assertEquals(others, watch(page(source, served[1].uri).state).value.songs.map { it.key })
    }

    @Test fun `a tap plays from that song on through the rest of the list`() = runTest {
        val state = watch(page(FakePageSource().apply { similar = served }).state).value
        assertEquals(served.drop(1).map { it.uri }, state.from(state.songs[1]))
        assertEquals(served.map { it.uri }, state.from(state.songs[0]))
    }

    private companion object {
        const val SONG = "library://track/7"
    }
}
