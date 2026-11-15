package io.github.hazymorning.amsel.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** A book on a speaker: skips by seconds, steps by chapter, moves at its speed. */
@RunWith(RobolectricTestRunner::class)
class RemotePlayerSpokenTest {
    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var now = 1_000_000L
    private val elapsedRealtime = { now }

    /** Three chapters of 90 s, as the test library's "The Lantern Road". */
    private val book = QueueEntry(
        "b1",
        "library://audiobook/2",
        "The Lantern Road",
        "Ada Quill",
        null,
        270,
        null,
        mediaType = "audiobook",
        chapterStartsMs = listOf(0L, 90_000L, 180_000L),
    )
    private val next = QueueEntry("b2", "library://audiobook/1", "Single File Tale", "Ada Quill", null, 120, null)

    private fun speaker(atSeconds: Double, entry: QueueEntry = book, speed: Double = 1.0) = FakeSpeaker(
        SpeakerSnapshot(
            name = "Kitchen",
            available = true,
            playing = true,
            current = entry,
            next = next,
            elapsedSeconds = atSeconds,
            elapsedAtMs = now,
            canSeek = true,
            canSkip = true,
            speed = speed,
        ),
        listOf(entry, next),
        elapsedRealtime,
    )

    private fun player(speaker: FakeSpeaker) = RemotePlayer(
        "kitchen",
        speaker.snapshots,
        speaker,
        elapsedRealtime,
        local = null,
        onCommandFailed = {},
        appScope = commandScope,
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun aCarTapOnTheBookOnTheSpeakerCarriesOnRatherThanStartingOver() {
        val speaker = speaker(600.0).apply { push { it.copy(playing = false) } }
        val p = player(speaker)
        p.setMediaItems(listOf(MediaItem.Builder().setMediaId(book.uri).build()))
        p.play()
        idle()
        assertEquals(listOf("play"), speaker.calls)
    }

    @Test fun aBookSkipsBySecondsAndSaysWhatItIs() {
        val p = player(speaker(100.0))
        assertTrue(p.isCommandAvailable(Player.COMMAND_SEEK_BACK))
        assertTrue(p.isCommandAvailable(Player.COMMAND_SEEK_FORWARD))
        assertEquals(SKIP_BACK_MS, p.seekBackIncrement)
        assertEquals(SKIP_FORWARD_MS, p.seekForwardIncrement)
        assertEquals(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK, p.mediaMetadata.mediaType)
        val song = player(speaker(100.0, entry = next))
        assertFalse(song.isCommandAvailable(Player.COMMAND_SEEK_BACK))
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, song.mediaMetadata.mediaType)
    }

    /** Each tap steps on from where the last one went, though the speaker is not there yet. */
    @Test fun quickSkipsAddUp() {
        val speaker = speaker(100.0)
        val p = player(speaker)
        p.seekBack()
        p.seekBack()
        p.seekBack()
        idle()
        assertEquals(listOf("seek:85", "seek:70", "seek:55"), speaker.calls)
        assertEquals(55_000, p.currentPosition)
    }

    @Test fun aSeekStandsAtItsTargetUntilTheSpeakerIsThere() {
        val speaker = speaker(100.0)
        val p = player(speaker)
        p.seekTo(200_000)
        idle()
        now += 4_000
        assertEquals(200_000, p.currentPosition)
        assertEquals(Player.STATE_BUFFERING, p.playbackState)
        assertTrue(p.playWhenReady)
        speaker.push { it.copy(elapsedSeconds = 200.3, elapsedAtMs = now, seeking = false) }
        idle()
        now += 2_000
        assertEquals(Player.STATE_READY, p.playbackState)
        assertEquals(202_300, p.currentPosition)
    }

    @Test fun aPausedBookSoughtStaysPausedAndShowsThePlace() {
        val speaker = speaker(100.0).apply { push { it.copy(playing = false) } }
        val p = player(speaker)
        p.seekForward()
        idle()
        assertEquals(listOf("seek:130"), speaker.calls)
        assertEquals(130_000, p.currentPosition)
        assertFalse(p.playWhenReady)
        assertEquals(Player.STATE_READY, p.playbackState)
    }

    @Test fun chapterStepsAddUpToo() {
        val speaker = speaker(10.0)
        val p = player(speaker)
        p.seekToNext()
        p.seekToNext()
        idle()
        assertEquals(listOf("seek:90", "seek:180"), speaker.calls)
        assertEquals("b1", p.currentMediaItem?.mediaId)
        assertEquals(180_000, p.currentPosition)
    }

    @Test fun aTappedChapterIsEnteredAndTheLastSecondIsKept() {
        val tapped = speaker(10.0, book.copy(chapterStartsMs = listOf(0L, 90_400L, 180_000L)))
        player(tapped).seekTo(90_400)
        idle()
        assertEquals(listOf("seek:91"), tapped.calls)
        val late = speaker(200.0, book.copy(chapterStartsMs = listOf(0L, 90_000L, 269_500L)))
        player(late).seekToNext()
        idle()
        assertEquals(listOf("seek:269"), late.calls)
    }

    @Test fun forwardStopsShortOfTheEnd() {
        val speaker = speaker(260.0)
        player(speaker).seekForward()
        idle()
        assertEquals(listOf("seek:269"), speaker.calls)
    }

    @Test fun nextAndPreviousStepByChapter() {
        val speaker = speaker(100.0)
        val p = player(speaker)
        p.seekToNext()
        idle()
        assertEquals("seek:180", speaker.calls.last())
        // In the last chapter, next is the queue's next item.
        p.seekToNext()
        idle()
        assertEquals("next", speaker.calls.last())
    }

    @Test fun previousRestartsTheChapterThenGoesBack() {
        val restart = speaker(100.0)
        player(restart).seekToPrevious()
        idle()
        assertEquals(listOf("seek:90"), restart.calls)
        // The first chapter starts at 0:00, which the server would take as "resume": 1 s instead.
        val back = speaker(92.0)
        player(back).seekToPrevious()
        idle()
        assertEquals(listOf("seek:1"), back.calls)
    }

    @Test fun aChapterThatStartsMidSecondIsEnteredNotMissed() {
        val speaker = speaker(10.0, book.copy(chapterStartsMs = listOf(0L, 90_400L, 180_000L)))
        player(speaker).seekToNext()
        idle()
        assertEquals(listOf("seek:91"), speaker.calls)
    }

    @Test fun aChapterStepStaysOnTheBookWhileItLands() {
        val speaker = speaker(100.0)
        // The place has not reached the snapshot yet: Media3 holds it meanwhile.
        val silent = object : SpeakerCommands by speaker {
            override fun seek(playerId: String, positionSeconds: Int, step: Boolean) = Unit
        }
        val p =
            RemotePlayer(
                "kitchen",
                speaker.snapshots,
                silent,
                elapsedRealtime,
                local = null,
                onCommandFailed = {},
                appScope = commandScope,
            )
        p.seekToNext()
        idle()
        assertEquals("b1", p.currentMediaItem?.mediaId)
        assertEquals(180_000, p.currentPosition)
    }

    @Test fun theBookMovesAtItsSpeed() {
        val p = player(speaker(100.0, speed = 1.5))
        now += 10_000
        assertEquals(115_000, p.currentPosition)
        assertEquals(1.5f, p.playbackParameters.speed, 0f)
    }
}
