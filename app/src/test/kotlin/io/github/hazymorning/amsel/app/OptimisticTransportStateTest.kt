package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.sample.SampleData
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.positionAt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OptimisticTransportStateTest {
    private val id = "kitchen"

    // Plays at volume 40, as in SampleData.
    private val reported = SampleData.nowPlaying.copy(
        positionMs = 1_000,
        positionAtMs = 0,
        shuffle = false,
        repeat = RepeatMode.Off,
    )

    private fun TestScope.transport() = OptimisticTransportState(
        backgroundScope,
        elapsedRealtime = { testScheduler.currentTime },
        timeoutMs = 3_000,
        volumeTimeoutMs = 1_500,
    )

    private fun OptimisticTransportState.shown(np: NowPlayingUi = reported) = overlay(np, pending.value[id])

    @Test fun everyTapShowsBeforeTheSpeakerAnswers() = runTest {
        val t = transport()
        t.playing(id, false)
        t.shuffle(id, true)
        t.repeat(id, RepeatMode.One)
        t.volume(id, 70)
        val shown = t.shown()
        assertFalse(shown.isPlaying)
        assertTrue(shown.shuffle)
        assertEquals(RepeatMode.One, shown.repeat)
        assertEquals(70, shown.volume)
    }

    @Test fun aRequestStandsUntilTheSpeakerReportsIt() = runTest {
        val t = transport()
        t.shuffle(id, true)
        // An event sent before the speaker took the tap still says off.
        t.settle(id, reported)
        assertTrue(t.shown().shuffle)
        t.settle(id, reported.copy(shuffle = true))
        assertTrue(t.pending.value.isEmpty())
        // From then on the speaker's own state shows, whatever changes it next.
        assertFalse(t.shown().shuffle)
    }

    @Test fun aRequestTheSpeakerNeverReportsRunsOut() = runTest {
        val t = transport()
        advanceTimeBy(500)
        t.repeat(id, RepeatMode.All)
        t.volume(id, 10)
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(40, t.shown().volume)
        assertEquals(RepeatMode.All, t.shown().repeat)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(RepeatMode.Off, t.shown().repeat)
    }

    @Test fun aLaterRequestKeepsItsOwnTime() = runTest {
        val t = transport()
        t.volume(id, 50)
        advanceTimeBy(1_000)
        t.volume(id, 60)
        advanceTimeBy(600)
        runCurrent()
        assertEquals(60, t.shown().volume)
    }

    @Test fun aFailedCommandShowsTheSpeakersStateAgain() = runTest {
        val t = transport()
        t.playing(id, false)
        t.clear(id)
        assertTrue(t.shown().isPlaying)
    }

    @Test fun pauseStopsTheSeekLineWhereItStood() = runTest {
        val t = transport()
        advanceTimeBy(4_000)
        t.playing(id, false)
        val shown = t.shown()
        assertFalse(shown.isPlaying)
        assertEquals(5_000, shown.positionMs)
        assertEquals(4_000, shown.positionAtMs)
    }

    // The speaker starts a moment after the tap; a running position would be ahead of the sound.
    @Test fun playShowsAtOnceAndItsPositionStandsUntilTheSpeakerPlays() = runTest {
        val t = transport()
        val paused = reported.copy(isPlaying = false, positionMs = 30_000, positionAtMs = 0)
        advanceTimeBy(60_000)
        t.playing(id, true)
        val shown = t.shown(paused)
        assertTrue(shown.isPlaying)
        assertFalse(shown.runs)
        advanceTimeBy(1_500)
        assertEquals(30_000, shown.positionAt(testScheduler.currentTime))
        val playing = paused.copy(isPlaying = true, positionMs = 30_200, positionAtMs = 61_400)
        t.settle(id, playing)
        assertTrue(t.shown(playing).runs)
    }

    @Test fun aSkipShowsTheNextSongFromItsStartUntilTheSpeakerReportsIt() = runTest {
        val t = transport()
        val next = reported.copy(title = "Undertow", key = "library://track/23", positionMs = 0, volume = 10)
        advanceTimeBy(2_000)
        t.item(id, next)
        val shown = t.shown()
        assertEquals("Undertow", shown.title)
        assertEquals(0, shown.positionAt(2_000))
        assertEquals(0, shown.positionAt(4_000))
        // Volume and controls stay the speaker's, not the next item's.
        assertEquals(40, shown.volume)
        t.settle(id, reported)
        assertEquals("Undertow", t.shown().title)
        t.settle(id, next)
        assertTrue(t.pending.value.isEmpty())
    }

    // The seek target comes with the speaker's report, standing (arriving); a pause keeps it.
    @Test fun aPauseDuringASeekStopsAtItsTarget() = runTest {
        val t = transport()
        val seeking = reported.copy(positionMs = 10_000, positionAtMs = 0, arriving = true)
        advanceTimeBy(2_000)
        t.playing(id, false)
        val shown = t.shown(seeking)
        assertFalse(shown.isPlaying)
        assertEquals(10_000, shown.positionMs)
        assertEquals(10_000, shown.positionAt(9_000))
    }

    @Test fun aSpeakerWithoutVolumeControlStaysWithout() = runTest {
        val t = transport()
        t.volume(id, 70)
        assertEquals(null, t.shown(reported.copy(volume = null)).volume)
    }
}
