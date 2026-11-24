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

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadProgressSyncTest {
    private val scope = TestScope()
    private val network = MutableStateFlow<NetworkKey?>(TRAIN)
    private val book = DownloadManifest(
        uri = "library://audiobook/2",
        absItemId = "book1",
        title = "Das dicke Buch",
        mediaType = "audiobook",
        durationSec = 36_000.0,
    )

    private val other = book.copy(uri = "library://audiobook/3", absItemId = "book2", title = "Das dünne Buch")

    private var now: DownloadManifest? = book

    private var kept = 0
    private val keptOf = mutableListOf<String>()

    private val sentOf = mutableListOf<String>()

    /** Failed sends included. */
    private var sent = 0

    private var unreachable = false

    private val saves = DownloadProgressSync(
        scope,
        network,
        place = { now?.let { DownloadPosition(it, LocalProgress(61.5, updatedAt = 0)) } },
        keep = {
            kept++
            keptOf += it.manifest.uri
        },
        send = {
            sent++
            sentOf += it.manifest.uri
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

    @Test fun `a book let go for another keeps and sends its own place, and nothing of the other at 0 s`() {
        saves.playing(true)
        // As LocalPlayback.play: save, stop, load the other; Media3's stop event comes after.
        saves.save(send = true)
        saves.stop()
        now = other
        saves.playing(false)
        scope.runCurrent()
        assertEquals(listOf(book.uri), keptOf)
        assertEquals(listOf(book.uri), sentOf)
    }

    @Test fun `after a send fails none goes while it plays until the network changes`() {
        unreachable = true
        saves.playing(true)
        after(15 * MINUTE)
        assertEquals(1, sent)
        assertEquals(90, kept)
        // A pause still sends: the user resumes from that place.
        saves.playing(false)
        scope.runCurrent()
        assertEquals(2, sent)
        saves.playing(true)
        after(5 * MINUTE)
        assertEquals(2, sent)
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
