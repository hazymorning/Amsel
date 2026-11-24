package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.data.CurrentItemStatus
import io.github.hazymorning.amsel.data.ItemPosition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One speaker's seeks: as few as the taps mean, one at a time, and none for a speaker that does not play. */
@OptIn(ExperimentalCoroutinesApi::class)
class SeekControllerTest {
    private val scope = TestScope()
    private val seeks = FakeSeekTracker(CurrentItemStatus(BOOK, playing = true))

    /** What reached the server, in order. */
    private val sent = mutableListOf<String>()

    /** Set to make the server hold its answer to a seek until it is completed. */
    private var answer: CompletableDeferred<Unit>? = null
    private var refuse: Exception? = null
    private val failures = mutableListOf<Exception>()
    private val retries = mutableListOf<() -> Unit>()

    private val controller = SeekController(
        "kitchen",
        seeks,
        scope,
        { scope.currentTime },
        seekOnServer = { seconds ->
            sent += "seek $seconds"
            refuse?.let { throw it }
            answer?.await()
        },
        pauseOnServer = { sent += "pause" },
        onFailed = { error, retry ->
            failures += error
            retries += retry
        },
    )

    private fun after(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    private fun play() {
        scope.launch { controller.play { sent += "play" } }
        scope.runCurrent()
    }

    /** Sends a play or a skip with [send]; the server answers it once the result is completed. */
    private fun starts(send: suspend (suspend () -> Unit) -> Unit): CompletableDeferred<Unit> {
        val started = CompletableDeferred<Unit>()
        scope.launch {
            try {
                send {
                    sent += "start"
                    started.await()
                }
            } catch (e: MaCommandException) {
                failures += e
            }
        }
        scope.runCurrent()
        return started
    }

    @Test fun `taps that follow one another become one seek, and each shows at once`() {
        controller.seek(130, step = true)
        assertEquals(listOf("startSeek 1 kitchen 130.0 $BOOK"), seeks.calls)
        after(300)
        controller.seek(160, step = true)
        after(300)
        controller.seek(190, step = true)
        after(SeekController.SETTLE_MS - 1)
        assertTrue(sent.isEmpty())
        after(1)
        assertEquals(listOf("seek 190"), sent)
        assertEquals(listOf("sent 3", "answered 3"), seeks.calls.takeLast(2))
    }

    @Test fun `a place picked goes out at once`() {
        controller.seek(960, step = false)
        after(0)
        assertEquals(listOf("seek 960"), sent)
    }

    @Test fun `the next seek waits for the answer to the one before, and only the latest waits`() {
        answer = CompletableDeferred()
        controller.seek(500, step = false)
        after(0)
        controller.seek(600, step = false)
        controller.seek(700, step = false)
        after(60_000)
        assertEquals(listOf("seek 500"), sent)
        answer?.complete(Unit)
        after(0)
        assertEquals(listOf("seek 500", "seek 700"), sent)
    }

    /** Twenty taps half a second apart sent twenty seeks before: forty leftover decoders on the server. */
    @Test fun `twenty taps half a second apart send one seek`() {
        repeat(20) { tap ->
            controller.seek(300 + tap * 30, step = true)
            after(500)
        }
        after(SeekController.SETTLE_MS)
        assertEquals(listOf("seek 870"), sent)
    }

    @Test fun `a speaker that does not play is sent nothing, and its next play starts where it was put`() {
        seeks.currentItemStatus = CurrentItemStatus(BOOK, playing = false)
        controller.seek(960, step = false)
        controller.seek(990, step = true)
        after(5_000)
        assertTrue(sent.isEmpty())
        assertEquals(ItemPosition(BOOK, 990.0), seeks.pausedPosition)
        play()
        assertEquals(listOf("seek 990"), sent)
        assertNull(seeks.pausedPosition)
    }

    @Test fun `a play of a queue at its own place is a play`() {
        seeks.currentItemStatus = CurrentItemStatus(BOOK, playing = false)
        play()
        assertEquals(listOf("play"), sent)
    }

    @Test fun `a paused position at the start of a book plays from its first second`() {
        seeks.currentItemStatus = CurrentItemStatus(BOOK, playing = false)
        seeks.pausedPosition = ItemPosition(BOOK, 0.4)
        play()
        assertEquals(listOf("seek 1"), sent)
    }

    @Test fun `a pause before the skip went out keeps its place for the next play`() {
        controller.seek(130, step = true)
        controller.paused()
        after(5_000)
        assertTrue(sent.isEmpty())
        assertEquals(ItemPosition(BOOK, 130.0), seeks.pausedPosition)
        play()
        assertEquals(listOf("seek 130"), sent)
    }

    @Test fun `a pause while a seek is on its way keeps its place, and pauses once the stream has started`() {
        answer = CompletableDeferred()
        controller.seek(500, step = false)
        after(0)
        controller.paused()
        assertEquals(ItemPosition(BOOK, 500.0), seeks.pausedPosition)
        after(10_000)
        assertEquals(listOf("seek 500"), sent)
        answer?.complete(Unit)
        after(0)
        assertEquals(listOf("seek 500", "pause"), sent)
    }

    @Test fun `a play after that pause plays from the place, and no pause follows`() {
        answer = CompletableDeferred()
        controller.seek(500, step = false)
        after(0)
        controller.paused()
        play()
        answer?.complete(Unit)
        after(0)
        assertEquals(listOf("seek 500", "seek 500"), sent)
    }

    /** The speaker reports its pause a moment later; until then it still says it plays. */
    @Test fun `a seek right after a pause becomes the paused position, though the speaker still reports playing`() {
        controller.paused()
        controller.seek(300, step = false)
        after(1_000)
        assertTrue(sent.isEmpty())
        assertEquals(ItemPosition(BOOK, 300.0), seeks.pausedPosition)
    }

    @Test fun `a seek right after a play goes out, though the speaker does not report playing yet`() {
        seeks.currentItemStatus = CurrentItemStatus(BOOK, playing = false)
        play()
        controller.seek(300, step = false)
        after(0)
        assertEquals(listOf("play", "seek 300"), sent)
    }

    @Test fun `once the speaker has had time to report, its own word decides`() {
        controller.paused()
        after(SeekController.WISH_MS)
        controller.seek(300, step = false)
        after(0)
        assertEquals(listOf("seek 300"), sent)
    }

    @Test fun `a step to another item drops a skip that waits`() {
        controller.seek(130, step = true)
        controller.dropped()
        after(5_000)
        assertTrue(sent.isEmpty())
        assertEquals("failed 1", seeks.calls.last())
    }

    @Test fun `a skip made on an item that has since ended is dropped`() {
        controller.seek(130, step = true)
        seeks.currentItemStatus = CurrentItemStatus(OTHER, playing = true)
        after(5_000)
        assertTrue(sent.isEmpty())
        assertEquals("failed 1", seeks.calls.last())
    }

    @Test fun `a seek into a book that is only starting goes out`() {
        seeks.currentItemStatus = CurrentItemStatus(OTHER, playing = true)
        controller.seekIn(ItemPosition(BOOK, 961.0))
        after(0)
        assertEquals(listOf("seek 961"), sent)
        assertEquals("startSeek 1 kitchen 961.0 $BOOK", seeks.calls.first())
    }

    /** AirPlay, or one of the speaker's inputs: the queue is not what plays, and the seek is the source's. */
    @Test fun `while another source plays, a seek goes out and nothing is shown from here`() {
        seeks.currentItemStatus = null
        controller.seek(40, step = true)
        controller.seek(70, step = true)
        after(SeekController.SETTLE_MS)
        assertEquals(listOf("seek 70"), sent)
        assertTrue(seeks.calls.isEmpty())
        controller.paused()
        controller.seek(90, step = false)
        after(0)
        assertEquals(listOf("seek 70", "seek 90"), sent)
    }

    @Test fun `a seek that fails says so, shows where the book still is, and can be sent again`() {
        refuse = MaCommandException("players/cmd/seek", 12, "out of range")
        controller.seek(500, step = false)
        after(0)
        assertEquals(1, failures.size)
        assertEquals("failed 1", seeks.calls.last())
        refuse = null
        retries.single()()
        after(0)
        assertEquals(listOf("seek 500", "seek 500"), sent)
        assertEquals("answered 2", seeks.calls.last())
    }

    @Test fun `a failure says nothing when a newer seek waits`() {
        answer = CompletableDeferred()
        controller.seek(500, step = false)
        after(0)
        controller.seek(700, step = false)
        answer?.completeExceptionally(MaCommandException("players/cmd/seek", 12, "out of range"))
        answer = null
        after(0)
        assertTrue(failures.isEmpty())
        assertEquals(listOf("seek 500", "seek 700"), sent)
    }

    /** The server takes a pause while it starts a play, and the speaker plays all the same (live). */
    @Test fun `a pause while a play is on its way pauses once the speaker has started`() {
        val started = starts { controller.play(it) }
        controller.paused()
        after(10_000)
        assertEquals(listOf("start"), sent)
        started.complete(Unit)
        after(0)
        assertEquals(listOf("start", "pause"), sent)
    }

    @Test fun `a pause while a skip is on its way pauses once the next item has started`() {
        val started = starts { controller.skip(it) }
        controller.paused()
        started.complete(Unit)
        after(0)
        assertEquals(listOf("start", "pause"), sent)
    }

    @Test fun `playback asked for again while it starts lets that pause go`() {
        val started = starts { controller.play(it) }
        controller.paused()
        controller.starting()
        started.complete(Unit)
        after(0)
        assertEquals(listOf("start"), sent)
    }

    @Test fun `a play that fails is followed by no pause`() {
        val started = starts { controller.play(it) }
        controller.paused()
        started.completeExceptionally(MaCommandException("players/cmd/play", 5, "unavailable"))
        after(0)
        assertEquals(listOf("start"), sent)
    }

    @Test fun `a pause told of a start elsewhere pauses once the stream has started, and once only`() {
        controller.paused(starting = true)
        repeat(2) {
            scope.launch { controller.streamStarted() }
            after(0)
        }
        assertEquals(listOf("pause"), sent)
    }

    @Test fun `a pause with nothing on its way is followed by none`() {
        play()
        controller.paused()
        scope.launch { controller.streamStarted() }
        after(0)
        assertEquals(listOf("play"), sent)
    }

    @Test fun `a book paused while it starts rests at its chapter`() {
        controller.starting()
        controller.paused(starting = true)
        controller.seekIn(ItemPosition(BOOK, 961.0))
        after(0)
        assertTrue(sent.isEmpty())
        assertEquals(ItemPosition(BOOK, 961.0), seeks.pausedPosition)
    }

    @Test fun `a play from a paused position that fails keeps the paused position`() {
        seeks.currentItemStatus = CurrentItemStatus(BOOK, playing = false)
        seeks.pausedPosition = ItemPosition(BOOK, 7230.6)
        refuse = MaCommandException("players/cmd/seek", 5, "unavailable")
        play()
        assertEquals(listOf("seek 7230"), sent)
        assertEquals(ItemPosition(BOOK, 7230.6), seeks.pausedPosition)
        assertEquals(1, failures.size)
    }

    private companion object {
        const val BOOK = "library://audiobook/1"
        const val OTHER = "library://track/7"
    }
}
