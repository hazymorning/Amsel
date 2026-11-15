package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.data.NetworkKey
import io.github.hazymorning.amsel.data.downloads.AbsException
import io.github.hazymorning.amsel.data.downloads.DownloadManifest
import io.github.hazymorning.amsel.data.downloads.LocalProgress
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

/** A downloaded book's place: on the phone every 10 s, at Audiobookshelf seldom, and not again in vain. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressSavesTest {
    private val scope = TestScope()
    private val network = MutableStateFlow<NetworkKey?>(TRAIN)
    private val book = DownloadManifest(
        uri = "library://audiobook/2",
        absItemId = "book1",
        title = "Das dicke Buch",
        mediaType = "audiobook",
        durationSec = 36_000.0,
    )

    /** Saves kept on the phone. */
    private var kept = 0

    /** Sends to Audiobookshelf, through or not. */
    private var sent = 0

    /** Audiobookshelf has a home address, which the train's network cannot reach. */
    private var unreachable = false

    private val saves = ProgressSaves(
        scope,
        network,
        place = { DownloadPlace(book, LocalProgress(61.5, updatedAt = 0)) },
        keep = { kept++ },
        send = {
            sent++
            if (unreachable) throw AbsException(AbsException.Reason.Unreachable)
        },
        io = EmptyCoroutineContext,
    )

    private fun after(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    @Test fun `ten minutes of playing send twice, and the pause once more`() {
        saves.playing(true)
        after(10 * MINUTE)
        assertEquals(60, kept)
        assertEquals(2, sent)
        saves.playing(false)
        scope.runCurrent()
        assertEquals(61, kept)
        assertEquals(3, sent)
    }

    @Test fun `after a send fails none goes while it plays until the network changes`() {
        unreachable = true
        saves.playing(true)
        after(15 * MINUTE)
        assertEquals(1, sent)
        assertEquals(90, kept)
        // A pause still sends: where it stopped is where the person goes on from.
        saves.playing(false)
        scope.runCurrent()
        assertEquals(2, sent)
        saves.playing(true)
        after(5 * MINUTE)
        assertEquals(2, sent)
        // Home again, on Wi-Fi.
        unreachable = false
        network.value = HOME
        after(5 * MINUTE)
        assertEquals(3, sent)
        after(5 * MINUTE)
        assertEquals(4, sent)
    }

    @Test fun `a book let go is saved no more`() {
        saves.playing(true)
        after(MINUTE)
        saves.stop()
        after(10 * MINUTE)
        assertEquals(6, kept)
        assertEquals(0, sent)
    }

    private companion object {
        const val MINUTE = 60_000L
        val TRAIN = NetworkKey(boot = 7, handles = listOf(201L))
        val HOME = NetworkKey(boot = 7, handles = listOf(100L))
    }
}
