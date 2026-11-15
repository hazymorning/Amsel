package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaClient
import io.github.hazymorning.amsel.api.MaEvent
import io.github.hazymorning.amsel.api.MaJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a queue shows while the app has put it somewhere, with the events Music Assistant sent for
 * it (2.10.9 and 2.11.0b7 live, to a Sendspin player, times as recorded): after the target it
 * reports the old stream's time added to it, or 0, until the new stream runs.
 */
class SeeksTest {
    private var now = 0L
    private val api = MaApi(MaClient(OkHttpClient(), CoroutineScope(Dispatchers.Unconfined)))

    private fun speakers(scope: CoroutineScope? = null) = Speakers(api, { now }, scope)

    private fun queue(
        state: String,
        elapsed: Double,
        inProgress: Boolean,
        uri: String = BOOK,
        type: String = "audiobook",
        active: Boolean = true,
    ) = MaEvent(
        "queue_updated",
        PHONE,
        MaJson.parseToJsonElement(
            """
            {"queue_id": "$PHONE", "active": $active, "state": "$state", "elapsed_time": $elapsed,
             "extra_attributes": {"play_action_in_progress": $inProgress},
             "current_item": {"queue_item_id": "q-$uri", "name": "Das lange Buch",
                "media_item": {"item_id": "1", "provider": "library", "name": "Das lange Buch",
                               "uri": "$uri", "media_type": "$type", "duration": 10800}}}
            """.trimIndent(),
        ),
    )

    private fun time(seconds: Double) = MaEvent("queue_time_updated", PHONE, JsonPrimitive(seconds))

    private fun player(activeSource: String, powered: Boolean = true) = MaEvent(
        "player_updated",
        PHONE,
        MaJson.parseToJsonElement(
            """{"player_id": "$PHONE", "name": "Kitchen", "active_source": "$activeSource", "powered": $powered}""",
        ),
    )

    private fun Speakers.state(): QueueState = queues.value.getValue(PHONE)

    private fun Speakers.at(): PositionAnchor = state().position

    private fun Speakers.shown(): Double = at().seconds

    /** A seek asked for and sent at once. */
    private fun Speakers.flyNow(seconds: Double, uri: String = BOOK): Long =
        fly(PHONE, Place(uri, seconds)).also { sent(PHONE, it) }

    /** The live trace of a seek to 600 s, 7.4 s after the stream before it started. */
    @Test fun `a seek stands at its target while the server reports the old stream on top of it`() {
        val s = speakers()
        s.onEvent(queue("playing", 6.3, inProgress = false))
        now = 7_960
        val ticket = s.flyNow(600.0)
        assertEquals(PositionAnchor(600.0, 7_960), s.at())
        assertTrue(s.state().seeking)
        now = 7_970
        s.onEvent(queue("playing", 600.0, inProgress = true))
        s.onEvent(queue("playing", 607.41, inProgress = false))
        s.onEvent(time(607.41))
        now = 7_980
        s.answered(PHONE, ticket)
        assertEquals(600.0, s.shown(), 0.0)
        assertTrue(s.state().seeking)
        now = 8_060
        s.onEvent(queue("playing", 607.41, inProgress = false))
        assertEquals(600.0, s.shown(), 0.0)
        // The new stream's time, a third of a second after the answer: kept, while the server has its moment.
        now = 8_320
        s.onEvent(time(600.25))
        assertEquals(600.0, s.shown(), 0.0)
        assertTrue(s.state().seeking)
        now = 9_210
        s.onEvent(time(601.15))
        assertEquals(PositionAnchor(601.15, 9_210), s.at())
        assertFalse(s.state().seeking)
        // Settled: the server's word counts again, also for a jump nobody here asked for.
        now = 12_000
        s.onEvent(time(1_100.0))
        assertEquals(1_100.0, s.shown(), 0.0)
    }

    /**
     * Found by the app's journey against 2.10.9 and 2.11.0b7: a song two seconds old, a seek to 4 s,
     * and the server says 6.1, close enough to pass for the place sought. Its next word is right.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the old stream's time on a young stream never shows`() {
        val scope = TestScope()
        val s = speakers(scope)
        s.onEvent(queue("playing", 2.1, inProgress = false))
        now = 100
        val ticket = s.flyNow(4.0)
        now = 110
        s.onEvent(queue("playing", 4.0, inProgress = true))
        s.onEvent(queue("playing", 6.148, inProgress = false))
        s.answered(PHONE, ticket)
        now = 200
        s.onEvent(time(6.148))
        assertEquals(4.0, s.shown(), 0.0)
        now = 460
        s.onEvent(time(4.25))
        assertEquals(4.0, s.shown(), 0.0)
        assertTrue(s.state().seeking)
        // No further report comes while it plays on: the latest counts once the moment is over.
        now = 110 + Seek.SETTLE_MS + 1
        scope.advanceTimeBy(Seek.SETTLE_MS + 1)
        scope.runCurrent()
        assertEquals(PositionAnchor(4.25, 460), s.at())
        assertFalse(s.state().seeking)
    }

    /** A paused speaker starts playing for a seek; its report came in the same moment as the answer, and none after. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the report that came with the answer counts`() {
        val scope = TestScope()
        val s = speakers(scope)
        s.onEvent(queue("playing", 5.0, inProgress = false))
        now = 9_670
        val ticket = s.flyNow(2_000.0)
        now = 9_680
        s.onEvent(queue("idle", 2_000.0, inProgress = true))
        now = 10_150
        s.onEvent(queue("playing", 2_000.25, inProgress = false))
        s.answered(PHONE, ticket)
        assertTrue(s.state().seeking)
        now = 10_150 + Seek.SETTLE_MS + 1
        scope.advanceTimeBy(Seek.SETTLE_MS + 1)
        scope.runCurrent()
        assertEquals(PositionAnchor(2_000.25, 10_150), s.at())
        assertFalse(s.state().seeking)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a book started at a chapter does not show where it was before`() {
        val scope = TestScope()
        val s = speakers(scope)
        s.onEvent(queue("playing", 1_106.65, inProgress = false))
        // Chapter 25 of 30 starts at 16:00.
        val ticket = s.flyNow(960.0)
        assertEquals(960.0, s.shown(), 0.0)
        now = 20
        s.onEvent(queue("playing", 1_109.07, inProgress = true))
        now = 60
        s.onEvent(queue("playing", 1_109.67, inProgress = false))
        s.answered(PHONE, ticket)
        assertEquals(960.0, s.shown(), 0.0)
        // The server resumes half a second early, and says so twice (live: the same 959.25 came
        // again 5 ms later): the chapter shows from its start all the same.
        now = 430
        s.onEvent(time(959.25))
        now = 435
        s.onEvent(queue("playing", 959.25, inProgress = false))
        assertEquals(960.0, s.shown(), 0.0)
        now = 60 + Seek.SETTLE_MS + 1
        scope.advanceTimeBy(Seek.SETTLE_MS + 1)
        scope.runCurrent()
        assertEquals(PositionAnchor(960.0, 435 + 750), s.at())
        assertFalse(s.state().seeking)
        // Once it reports the chapter reached, its word counts again.
        now = 2_000
        s.onEvent(time(961.0))
        now = 3_000
        s.onEvent(time(1_500.0))
        assertEquals(1_500.0, s.shown(), 0.0)
    }

    @Test fun `a report of 0 while the new stream starts is not taken for where the book is`() {
        val s = speakers()
        s.onEvent(queue("playing", 400.0, inProgress = false))
        val ticket = s.flyNow(985.0)
        s.answered(PHONE, ticket)
        now = 300
        s.onEvent(time(0.0))
        assertEquals(985.0, s.shown(), 0.0)
        now = 1_200
        s.onEvent(time(985.9))
        assertEquals(985.9, s.shown(), 0.0)
    }

    /**
     * The owner's test of 2026.11.9: a chapter picked deep in a long file played, and Now Playing
     * showed chapter 1. The server answers a seek once the new stream runs, which can take minutes,
     * and says 0 meanwhile.
     */
    @Test fun `a seek the server answers late stands at its target until it answers`() {
        val s = speakers()
        s.onEvent(queue("playing", 400.0, inProgress = false))
        val ticket = s.flyNow(7_100.0)
        now = 300
        s.onEvent(queue("playing", 0.0, inProgress = true))
        now = Seek.WINDOW_MS * 3
        s.onEvent(time(0.0))
        assertEquals(7_100.0, s.shown(), 0.0)
        assertTrue(s.state().seeking)
        s.answered(PHONE, ticket)
        now += 400
        s.onEvent(queue("playing", 7_100.0, inProgress = false))
        assertEquals(7_100.0, s.shown(), 0.0)
        now += 20_000
        s.onEvent(time(7_120.0))
        assertEquals(7_120.0, s.shown(), 0.0)
        assertFalse(s.state().seeking)
    }

    @Test fun `a target the server never reports gives way to what it says after the window`() {
        val s = speakers()
        s.onEvent(queue("playing", 400.0, inProgress = false))
        val ticket = s.flyNow(985.0)
        s.answered(PHONE, ticket)
        now = 300
        s.onEvent(time(12.0))
        assertEquals(985.0, s.shown(), 0.0)
        now = Seek.WINDOW_MS + 1
        s.onEvent(time(22.0))
        assertEquals(22.0, s.shown(), 0.0)
        assertFalse(s.state().seeking)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the server's word counts again when the window ends without another report`() {
        val scope = TestScope()
        val s = speakers(scope)
        s.onEvent(queue("playing", 400.0, inProgress = false))
        val ticket = s.flyNow(985.0)
        s.answered(PHONE, ticket)
        now = 300
        s.onEvent(time(12.0))
        scope.advanceTimeBy(Seek.WINDOW_MS)
        scope.runCurrent()
        assertEquals(985.0, s.shown(), 0.0)
        now = Seek.WINDOW_MS + 2
        scope.advanceTimeBy(2)
        scope.runCurrent()
        assertEquals(PositionAnchor(12.0, 300), s.at())
        assertFalse(s.state().seeking)
    }

    @Test fun `a seek that did not reach the server shows where the book still is`() {
        val s = speakers()
        s.onEvent(queue("playing", 400.0, inProgress = false))
        val before = s.at()
        now = 2_000
        val ticket = s.flyNow(985.0)
        s.failed(PHONE, ticket)
        assertEquals(before, s.at())
        assertFalse(s.state().seeking)
    }

    @Test fun `of two seeks only the later one's answer counts`() {
        val s = speakers()
        s.onEvent(queue("playing", 400.0, inProgress = false))
        val first = s.flyNow(985.0)
        now = 500
        val second = s.fly(PHONE, Place(BOOK, 1_500.0))
        // The first seek's answer and its stream: neither is where the second one goes.
        now = 900
        s.onEvent(queue("playing", 985.3, inProgress = false))
        s.answered(PHONE, first)
        assertEquals(1_500.0, s.shown(), 0.0)
        assertTrue(s.state().seeking)
        // Nor does a failure of the first take the second back.
        s.failed(PHONE, first)
        assertEquals(1_500.0, s.shown(), 0.0)
        now = 1_000
        s.sent(PHONE, second)
        now = 1_600
        s.onEvent(queue("playing", 1_500.2, inProgress = false))
        s.answered(PHONE, second)
        now = 2_700
        s.onEvent(time(1_501.3))
        assertEquals(1_501.3, s.shown(), 0.0)
        assertFalse(s.state().seeking)
    }

    @Test fun `another item's time is its own`() {
        val s = speakers()
        s.onEvent(queue("playing", 400.0, inProgress = false, uri = OTHER))
        val ticket = s.flyNow(960.0)
        // The other item still plays while the book starts: its time stays as reported.
        assertEquals(400.0, s.shown(), 0.0)
        assertFalse(s.state().seeking)
        now = 100
        s.onEvent(queue("playing", 401.0, inProgress = true, uri = OTHER))
        assertEquals(401.0, s.shown(), 0.0)
        now = 500
        s.onEvent(queue("playing", 1_000.0, inProgress = false))
        assertEquals(960.0, s.shown(), 0.0)
        assertTrue(s.state().seeking)
        s.answered(PHONE, ticket)
        now = 1_600
        s.onEvent(time(960.9))
        assertEquals(960.9, s.shown(), 0.0)
    }

    @Test fun `a seek on an item the queue has left is forgotten`() {
        val s = speakers()
        s.onEvent(queue("playing", 400.0, inProgress = false))
        s.flyNow(985.0)
        now = 200
        s.onEvent(queue("playing", 0.0, inProgress = false, uri = OTHER, type = "track"))
        assertEquals(0.0, s.shown(), 0.0)
        // Back on the book later, its time is the server's.
        now = 5_000
        s.onEvent(queue("playing", 30.0, inProgress = false))
        assertEquals(30.0, s.shown(), 0.0)
    }

    @Test fun `a seek fits the target and the time since it went out, at the book's speed`() {
        val seek = Seek.Flying(Place(BOOK, 100.0), ticket = 1, askedAtMs = 0, met = true, before = null)
        assertTrue(seek.fits(PositionAnchor(98.5, 0), speed = 1.0))
        assertFalse(seek.fits(PositionAnchor(97.0, 0), speed = 1.0))
        assertTrue(seek.fits(PositionAnchor(105.9, 2_000), speed = 1.5))
        assertFalse(seek.fits(PositionAnchor(106.1, 2_000), speed = 1.5))
        assertFalse(seek.fits(PositionAnchor(0.0, 300), speed = 1.0))
        // Counted from when the command went out, not from the tap that waited for others.
        assertFalse(seek.copy(sentAtMs = 1_500).fits(PositionAnchor(104.0, 2_000), speed = 1.0))
        // And no report ends it before the server has had its moment after the answer.
        assertFalse(seek.settled(5_000))
        assertFalse(seek.copy(answeredAtMs = 4_500).settled(5_000))
        assertTrue(seek.copy(answeredAtMs = 4_000).settled(5_000))
        assertEquals(PositionAnchor(100.0, 2_000), seek.notBefore(PositionAnchor(99.25, 1_500), speed = 1.5))
        assertEquals(PositionAnchor(101.0, 1_500), seek.notBefore(PositionAnchor(101.0, 1_500), speed = 1.0))
    }

    @Test fun `a place sought while paused shows at once and waits for the next play`() {
        val s = speakers()
        s.onEvent(queue("paused", 120.0, inProgress = false))
        now = 1_000
        s.park(PHONE, Place(BOOK, 960.0))
        assertEquals(PositionAnchor(960.0, 1_000), s.at())
        assertFalse(s.state().seeking)
        assertEquals(Place(BOOK, 960.0), s.parked(PHONE))
        // The server still has the queue where it paused, and says so again.
        now = 15_000
        s.onEvent(queue("paused", 120.0, inProgress = false))
        assertEquals(960.0, s.shown(), 0.0)
        // The play is a seek there.
        now = 20_000
        val ticket = s.flyNow(960.0)
        assertNull(s.parked(PHONE))
        now = 20_500
        s.onEvent(queue("playing", 960.25, inProgress = false))
        s.answered(PHONE, ticket)
        now = 21_600
        s.onEvent(time(961.3))
        assertEquals(961.3, s.shown(), 0.0)
    }

    /** The owner's wish: a skip, then a pause before the skip went out. The pause takes a moment to show. */
    @Test fun `a place parked with a pause on its way outlasts the server's last playing reports`() {
        val s = speakers()
        s.onEvent(queue("playing", 100.0, inProgress = false))
        now = 1_000
        s.park(PHONE, Place(BOOK, 130.0))
        // Paused, as far as anyone looking can tell, and standing at the place.
        assertFalse(s.state().isPlaying)
        assertEquals(130.0, s.state().secondsAt(now + 5_000), 0.0)
        now = 1_010
        s.onEvent(queue("playing", 101.0, inProgress = true))
        s.onEvent(queue("playing", 101.1, inProgress = false))
        assertEquals(130.0, s.shown(), 0.0)
        assertFalse(s.state().isPlaying)
        now = 1_800
        s.onEvent(queue("idle", 101.3, inProgress = false))
        assertEquals(130.0, s.shown(), 0.0)
        assertEquals(Place(BOOK, 130.0), s.parked(PHONE))
        // Once it has rested, playing means someone started it again: its own time counts.
        now = 60_000
        s.onEvent(queue("playing", 101.5, inProgress = false))
        assertEquals(101.5, s.shown(), 0.0)
        assertTrue(s.state().isPlaying)
        assertNull(s.parked(PHONE))
    }

    @Test fun `a pause that never reached the speaker does not hold a parked place`() {
        val s = speakers()
        s.onEvent(queue("playing", 100.0, inProgress = false))
        s.park(PHONE, Place(BOOK, 130.0))
        now = Seek.PAUSE_MS + 1
        s.onEvent(time(106.0))
        assertEquals(106.0, s.shown(), 0.0)
        assertNull(s.parked(PHONE))
    }

    @Test fun `a parked place belongs to its item`() {
        val s = speakers()
        s.onEvent(queue("paused", 120.0, inProgress = false))
        s.park(PHONE, Place(BOOK, 960.0))
        s.onEvent(queue("idle", 0.0, inProgress = false, uri = OTHER, type = "track"))
        assertEquals(0.0, s.shown(), 0.0)
        assertNull(s.parked(PHONE))
    }

    @Test fun `what a queue holds, and whether it plays or is about to`() {
        val s = speakers()
        assertNull(s.holding(PHONE))
        s.onEvent(queue("paused", 120.0, inProgress = false))
        assertEquals(Holding(BOOK, playing = false), s.holding(PHONE))
        s.onEvent(queue("idle", 120.0, inProgress = true))
        assertEquals(Holding(BOOK, playing = true, starting = true), s.holding(PHONE))
        s.onEvent(queue("idle", 120.0, inProgress = false))
        s.flyNow(500.0)
        assertEquals(Holding(BOOK, playing = true), s.holding(PHONE))
    }

    @Test fun `a start the server is making is over once it says so`() = runBlocking {
        val s = speakers()
        s.onEvent(queue("idle", 120.0, inProgress = true))
        val over = async(start = CoroutineStart.UNDISPATCHED) { s.started(PHONE) }
        assertFalse(over.isCompleted)
        s.onEvent(queue("playing", 120.0, inProgress = false))
        withTimeout(1_000) { over.await() }
        s.started(PHONE)
    }

    /** AirPlay or an input has the speaker: a seek there is that source's, not the queue's. */
    @Test fun `a queue that is not what its speaker plays holds nothing to seek in`() {
        val s = speakers()
        s.onEvent(queue("idle", 120.0, inProgress = false, active = false))
        s.onEvent(player(activeSource = "airplay"))
        assertNull(s.holding(PHONE))
        s.onEvent(player(activeSource = PHONE))
        assertEquals(Holding(BOOK, playing = false), s.holding(PHONE))
        s.onEvent(player(activeSource = PHONE, powered = false))
        assertNull(s.holding(PHONE))
    }

    /** The owner's case, live on 2.10.9: chapter 25 picked, the connection lost while it played. */
    private fun Speakers.rebuiltAfterChapter25() {
        onEvent(queue("playing", 7225.0, inProgress = false))
        now = 5_000
        left(PHONE)
        now = 15_000
        rejoined(PHONE)
        // The server's rebuild, from a cache written in chapter 1.
        onEvent(queue("idle", 127.0, inProgress = false))
    }

    @Test fun `a book keeps its place when the server rebuilds the phone's queue`() {
        val s = speakers()
        s.rebuiltAfterChapter25()
        assertEquals(7230.0, s.shown(), 0.0)
        assertEquals(Place(BOOK, 7230.0), s.parked(PHONE))
        now = 60_000
        s.onEvent(queue("idle", 127.0, inProgress = false))
        assertEquals(7230.0, s.shown(), 0.0)
    }

    @Test fun `the rebuilt queue may come before the join`() {
        val s = speakers()
        s.onEvent(queue("paused", 7225.0, inProgress = false))
        s.left(PHONE)
        s.onEvent(queue("idle", 127.0, inProgress = false))
        s.rejoined(PHONE)
        assertEquals(7225.0, s.shown(), 0.0)
    }

    @Test fun `a play starts the rebuilt queue where it was, and its time is the server's again`() {
        val s = speakers()
        s.rebuiltAfterChapter25()
        now = 20_000
        val ticket = s.flyNow(7230.0)
        s.onEvent(queue("idle", 7230.0, inProgress = true))
        now = 21_000
        s.onEvent(queue("playing", 7230.0, inProgress = false))
        s.answered(PHONE, ticket)
        assertNull(s.parked(PHONE))
        now = 40_000
        s.onEvent(queue("paused", 7249.0, inProgress = false))
        assertEquals(7249.0, s.shown(), 0.0)
    }

    @Test fun `a chapter picked on the rebuilt queue wins`() {
        val s = speakers()
        s.rebuiltAfterChapter25()
        s.park(PHONE, Place(BOOK, 8000.0))
        assertEquals(8000.0, s.shown(), 0.0)
        assertEquals(Place(BOOK, 8000.0), s.parked(PHONE))
    }

    @Test fun `music, or another item on the rebuilt queue, keeps the server's time`() {
        val s = speakers()
        s.onEvent(queue("playing", 200.0, inProgress = false, uri = OTHER, type = "track"))
        s.left(PHONE)
        s.rejoined(PHONE)
        s.onEvent(queue("idle", 12.0, inProgress = false, uri = OTHER, type = "track"))
        assertEquals(12.0, s.shown(), 0.0)
        assertNull(s.parked(PHONE))
        s.rebuiltAfterChapter25()
        s.onEvent(queue("idle", 0.0, inProgress = false, uri = OTHER, type = "track"))
        assertEquals(0.0, s.shown(), 0.0)
        assertNull(s.parked(PHONE))
    }

    @Test fun `a place left while a seek was on its way is the seek's target`() {
        val s = speakers()
        s.onEvent(queue("playing", 400.0, inProgress = false))
        s.flyNow(7_100.0)
        now = 4_000
        s.left(PHONE)
        s.rejoined(PHONE)
        s.onEvent(queue("idle", 380.0, inProgress = false))
        assertEquals(7_100.0, s.shown(), 0.0)
    }

    private companion object {
        const val PHONE = "up56aa9c86"
        const val BOOK = "library://audiobook/1"
        const val OTHER = "library://track/7"
    }
}
