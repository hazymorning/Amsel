package io.github.hazymorning.amsel.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import io.github.hazymorning.amsel.api.RepeatMode
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RemotePlayerTest {
    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var now = 1_000_000L
    private val elapsedRealtime = { now }
    private val queue =
        (1..3).map {
            QueueEntry("q$it", "library://track/$it", "Song $it", "Artist $it", "Album", 200, null)
        }

    private fun fake(transform: (SpeakerSnapshot) -> SpeakerSnapshot = { it }) = FakeSpeaker(
        transform(
            SpeakerSnapshot(
                name = "Kitchen",
                available = true,
                playing = true,
                current = queue[0],
                next = queue[1],
                elapsedSeconds = 10.0,
                elapsedAtMs = now,
                volumeLevel = 40,
                canSetVolume = true,
                canSeek = true,
                canSkip = true,
            ),
        ),
        queue,
        elapsedRealtime,
    )

    private fun player(speaker: FakeSpeaker, commands: SpeakerCommands = speaker) = RemotePlayer(
        "kitchen",
        speaker.snapshots,
        commands,
        elapsedRealtime,
        local = null,
        onCommandFailed = {},
        appScope = commandScope,
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun mapsAPlayingSpeaker() {
        val p = player(fake())
        assertEquals(Player.STATE_READY, p.playbackState)
        assertTrue(p.isPlaying)
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, p.deviceInfo.playbackType)
        assertEquals(100, p.deviceInfo.maxVolume)
        assertEquals(40, p.deviceVolume)
        assertEquals("q1", p.currentMediaItem?.mediaId)
        assertEquals(2, p.mediaItemCount)
        assertEquals("Artist 1 · Kitchen", p.mediaMetadata.artist.toString())
        assertTrue(p.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(p.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS))
    }

    /** A speaker not heard from yet (Amsel was closed); [onPlay] is what a play makes it report. */
    private fun silent(
        calls: MutableList<String>,
        onPlay: SpeakerSnapshot?,
    ): Pair<RemotePlayer, MutableStateFlow<SpeakerSnapshot?>> {
        val reported = MutableStateFlow<SpeakerSnapshot?>(null)
        val commands = object : SpeakerCommands by fake() {
            override suspend fun play(playerId: String) {
                calls += "play"
                reported.value = onPlay
            }

            override suspend fun playMedia(request: PlayRequest) {
                calls += "playMedia"
            }
        }
        val p = RemotePlayer("kitchen", reported, commands, elapsedRealtime, null, {}, commandScope)
        return p to reported
    }

    private val resume = MediaItem.Builder().setMediaId(BrowseIds.RESUME).build()

    @Test
    fun aMediaButtonResumesWhatTheSpeakerHoldsAndQueuesNothing() {
        val calls = mutableListOf<String>()
        val playing =
            SpeakerSnapshot(name = "Kitchen", available = true, playing = true, current = queue[0], elapsedAtMs = now)
        val (p, _) = silent(calls, playing)
        assertEquals(Player.STATE_IDLE, p.playbackState)
        p.setMediaItems(listOf(resume), 0, C.TIME_UNSET)
        // Something to show at once, while the speaker has not answered.
        assertEquals(BrowseIds.RESUME, p.currentMediaItem?.mediaId)
        p.play()
        idle()
        assertEquals(listOf("play"), calls)
        assertEquals("q1", p.currentMediaItem?.mediaId)
        assertTrue(p.isPlaying)
    }

    @Test
    fun aResumeWithNothingToResumeLeavesNothingBehind() {
        val calls = mutableListOf<String>()
        val (p, _) = silent(calls, onPlay = null)
        p.setMediaItems(listOf(resume), 0, C.TIME_UNSET)
        p.play()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(listOf("play"), calls)
        assertEquals(0, p.mediaItemCount)
        assertEquals(Player.STATE_IDLE, p.playbackState)
    }

    @Test
    fun extrapolatesThePositionFromTheAnchor() {
        val p = player(fake())
        assertEquals(10_000, p.currentPosition)
        now += 7_500
        assertEquals(17_500, p.currentPosition)
    }

    @Test
    fun aSpeakerOutOfReachForAWhileRestsWhereItWasWithItsSongStillShown() {
        // Heard playing 10 s in, with a play on its way; out of reach 5 s later.
        val p = player(fake { it.copy(playActionInProgress = true).standingAt(now + 5_000) })
        assertFalse(p.playWhenReady)
        assertEquals(Player.STATE_READY, p.playbackState)
        assertEquals("q1", p.currentMediaItem?.mediaId)
        now += 60_000
        assertEquals(15_000, p.currentPosition)
    }

    @Test
    fun aPausedPositionStaysPut() {
        val p = player(fake { it.copy(playing = false) })
        now += 30_000
        assertEquals(10_000, p.currentPosition)
        assertFalse(p.isPlaying)
        assertEquals(Player.STATE_READY, p.playbackState)
    }

    @Test
    fun nextReachesTheServerEvenWithoutANextItemInTheWindow() {
        val speaker = fake { it.copy(current = queue[2], next = null) }
        val p = player(speaker)
        assertFalse(p.hasNextMediaItem())
        p.seekToNext()
        idle()
        assertEquals(listOf("next"), speaker.calls)
    }

    @Test
    fun previousAlwaysReachesTheServer() {
        val speaker = fake { it.copy(current = queue[1], next = queue[2], elapsedSeconds = 2.0) }
        val p = player(speaker)
        p.seekToPrevious()
        idle()
        assertEquals(listOf("previous"), speaker.calls)
        assertEquals("q1", p.currentMediaItem?.mediaId)
    }

    @Test
    fun stopReachesTheSpeaker() {
        val speaker = fake()
        val p = player(speaker)
        assertTrue(p.isCommandAvailable(Player.COMMAND_STOP))
        p.stop()
        idle()
        assertEquals(listOf("stop"), speaker.calls)
        assertFalse(p.playWhenReady)
        // Stopped, it keeps its song and plays it on: the car keeps its playback screen.
        assertEquals(Player.STATE_READY, p.playbackState)
        assertEquals("q1", p.currentMediaItem?.mediaId)
    }

    @Test
    fun volumeKeysAndTheSliderMoveTheSpeaker() {
        val speaker = fake()
        val p = player(speaker)
        p.increaseDeviceVolume(C.VOLUME_FLAG_SHOW_UI)
        idle()
        p.setDeviceVolume(25, 0)
        idle()
        assertEquals(listOf("volume_up", "volume_set:25"), speaker.calls)
        assertEquals(25, p.deviceVolume)
    }

    @Test
    fun aSpeakerWithoutVolumeControlHasFixedVolume() {
        val p = player(fake { it.copy(canSetVolume = false) })
        assertEquals(0, p.deviceInfo.maxVolume)
        assertFalse(p.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))
    }

    @Test
    fun repeatOneWithTheSameNextItemDoesNotCrash() {
        val p = player(fake { it.copy(next = it.current, repeat = RepeatMode.One) })
        assertEquals(1, p.mediaItemCount)
        assertEquals(Player.REPEAT_MODE_ONE, p.repeatMode)
    }

    @Test
    fun anUnconfirmedPauseRevertsAfterTheTimeout() {
        val speaker = fake()
        val silent =
            object : SpeakerCommands by speaker {
                override suspend fun pause(playerId: String) = Unit // the server never confirms
            }
        val p = player(speaker, silent)
        p.pause()
        idle()
        assertFalse(p.playWhenReady) // optimistic placeholder
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertTrue(p.playWhenReady) // back to what the server says
    }

    @Test
    fun aCarPlayBecomesPlayMediaAndNoExtraPlay() {
        val speaker = fake { it.copy(current = null, next = null, playing = false) }
        val p = player(speaker)
        assertEquals(Player.STATE_IDLE, p.playbackState)
        val id = BrowseIds.track("library://album/7", "library://track/5")
        p.setMediaItems(listOf(MediaItem.Builder().setMediaId(id).build()))
        p.play()
        idle()
        assertEquals(listOf("play_media:library://album/7@library://track/5"), speaker.calls)
        assertEquals(Player.STATE_IDLE, p.playbackState) // no item yet, but play action pending
        assertTrue(p.playWhenReady)
    }

    @Test
    fun anAssistantPrepareStartsNothingUntilItsPlay() {
        val speaker = fake { it.copy(current = null, next = null, playing = false) }
        val p = player(speaker)
        p.setMediaItems(listOf(MediaItem.Builder().setMediaId("library://album/7").build()))
        p.prepare()
        idle()
        assertEquals(emptyList<String>(), speaker.calls)
        p.play()
        idle()
        assertEquals(listOf("play_media:library://album/7@null"), speaker.calls)
    }

    @Test
    fun aCarPlaySendsNoPlayBeforeTheSpeakerReportsIt() {
        val speaker = fake { it.copy(current = null, next = null, playing = false) }
        // As the real dispatcher does: the play is handed over, and the snapshot reports it later.
        val handedOver =
            object : SpeakerCommands by speaker {
                override suspend fun playMedia(request: PlayRequest) {
                    speaker.calls += "play_media"
                }
            }
        val p = player(speaker, handedOver)
        p.setMediaItems(listOf(MediaItem.Builder().setMediaId("library://album/7").build()))
        p.prepare()
        p.play()
        idle()
        speaker.push { it.copy(playActionInProgress = true) }
        idle()
        assertEquals(listOf("play_media"), speaker.calls)
        assertTrue(p.playWhenReady)
    }

    /** Paused while it started, then played again: the pause that waits for the start gives way. */
    @Test
    fun aPlayAskedAgainWhileThePlayStartsIsToldAndNotSent() {
        val speaker = fake { it.copy(playing = false, playActionInProgress = true) }
        val told = mutableListOf<String>()
        val commands =
            object : SpeakerCommands by speaker {
                override fun starting(playerId: String) {
                    told += playerId
                }
            }
        val p = player(speaker, commands)
        p.play()
        idle()
        assertEquals(listOf("kitchen"), told)
        assertTrue(speaker.calls.isEmpty())
    }

    @Test
    fun aSessionControllerSeesARemoteSpeaker() {
        val context = RuntimeEnvironment.getApplication()
        val p = player(fake())
        val session = MediaSession.Builder(context, p).build()
        val future = androidx.media3.session.MediaController.Builder(context, session.token).buildAsync()
        idle()
        val controller = future.get()
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, controller.deviceInfo.playbackType)
        assertEquals(100, controller.deviceInfo.maxVolume)
        assertEquals(40, controller.deviceVolume)
        assertTrue(controller.isPlaying)
        assertEquals("q1", controller.currentMediaItem?.mediaId)
        controller.setDeviceVolume(55, 0)
        idle()
        assertEquals(55, p.deviceVolume)
        controller.release()
        session.release()
        p.release()
    }
}
