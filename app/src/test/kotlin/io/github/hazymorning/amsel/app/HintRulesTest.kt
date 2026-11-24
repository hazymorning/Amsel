package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.api.PlayerQueue
import io.github.hazymorning.amsel.api.QueueItem
import io.github.hazymorning.amsel.data.PositionAnchor
import io.github.hazymorning.amsel.data.QueueState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HintRulesTest {
    private val song = QueueItem("q1", "Kestrel - Lanterns", duration = 200)
    private val next = QueueItem("q2", "Kestrel - Embers", duration = 180)

    private fun playing(atSecond: Double, current: QueueItem = song) = QueueState(
        PlayerQueue("kitchen", items = 3, state = "playing", currentItem = current, currentIndex = 2),
        PositionAnchor(atSecond, atElapsedMs = 0),
    )

    private fun stopped(nextItem: QueueItem? = null, dynamic: Boolean = false, state: String = "idle") = QueueState(
        PlayerQueue(
            "kitchen",
            items = 3,
            state = state,
            currentItem = song,
            currentIndex = 2,
            nextItem = nextItem,
            isDynamic = dynamic,
        ),
        PositionAnchor(0.0, atElapsedMs = 0),
    )

    @Test fun aQueueWhoseLastSongRanToItsEndRanOut() {
        assertTrue(queueEnded(playing(atSecond = 195.0), stopped(), nowMs = 2_000))
    }

    @Test fun aStopHalfwayOrMusicThatMovedAwayIsNoEnding() {
        assertFalse(queueEnded(playing(atSecond = 60.0), stopped(), nowMs = 2_000))
    }

    @Test fun aPauseSomethingStillToComeOrAMixIsNoEnding() {
        assertFalse(queueEnded(playing(atSecond = 199.0), stopped(state = "paused"), nowMs = 1_000))
        assertFalse(queueEnded(playing(atSecond = 199.0), stopped(nextItem = next), nowMs = 1_000))
        assertFalse(queueEnded(playing(atSecond = 199.0), stopped(dynamic = true), nowMs = 1_000))
    }

    @Test fun aSleepTimerIsOfferedFromNineInTheEveningToFiveInTheMorning() {
        assertTrue(atNight(21))
        assertTrue(atNight(23))
        assertTrue(atNight(0))
        assertTrue(atNight(4))
        assertFalse(atNight(5))
        assertFalse(atNight(13))
        assertFalse(atNight(20))
    }
}
