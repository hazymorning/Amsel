package io.github.hazymorning.amsel.playback

import android.os.Looper
import io.github.hazymorning.amsel.api.ConnectionState
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.ServerInfo
import io.music_assistant.sendspin.api.AudioPhase
import io.music_assistant.sendspin.api.AudioStatus
import io.music_assistant.sendspin.api.ClockQuality
import io.music_assistant.sendspin.api.FailureCause
import io.music_assistant.sendspin.api.PlayerState
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PhoneCommandsTest {
    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val elapsedRealtime = { 1_000L }
    private val queue = (1..3).map {
        QueueEntry("q$it", "library://track/$it", "Song $it", "Artist", "Album", 200, null)
    }
    private val speaker = FakeSpeaker(
        SpeakerSnapshot(
            name = "This phone",
            available = true,
            playing = false,
            current = queue[0],
            next = queue[1],
            canSkip = true,
        ),
        queue,
        elapsedRealtime,
    )

    private val sentTo = mutableListOf<String>()
    private val recording = object : SpeakerCommands by speaker {
        override suspend fun play(playerId: String) {
            sentTo += playerId
            speaker.play(playerId)
        }
    }

    private class FakePhone : LocalOutput {
        val joined = CompletableDeferred<String>()
        val holds = mutableListOf<Boolean>()

        override val id: String? = null

        override fun hold(held: Boolean) {
            holds += held
        }

        override suspend fun awaitConnected(): String = joined.await()
    }

    private val failures = mutableListOf<CommandFailure>()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun aCommandForThePhoneWaitsUntilItHasJoinedTheServer() {
        val phone = FakePhone()
        // A remembered id from an earlier run: the server forgets the phone while it is away.
        val p =
            RemotePlayer(
                "phone-1",
                speaker.snapshots,
                recording,
                elapsedRealtime,
                phone,
                failures::add,
                appScope = commandScope,
            )
        p.play()
        idle()
        assertEquals(listOf(false), phone.holds) // the local hold is released at once
        assertEquals(emptyList<String>(), sentTo)

        phone.joined.complete("phone-1")
        idle()
        assertEquals(listOf("phone-1"), sentTo)
        assertTrue(failures.isEmpty())
    }

    @Test fun aPhoneWithoutAnIdYetUsesTheIdItJoinedWith() {
        val phone = FakePhone()
        val p =
            RemotePlayer(
                "",
                speaker.snapshots,
                recording,
                elapsedRealtime,
                phone,
                failures::add,
                appScope = commandScope,
            )
        p.play()
        phone.joined.complete("phone-1")
        idle()
        assertEquals(listOf("phone-1"), sentTo)
    }

    @Test fun aCommandWaitingForThePhoneSurvivesTheSwapItsJoinCauses() {
        // The first join gives the phone its id, so the service swaps in a player for that id
        // while this one's command is still waiting for the join.
        val phone = FakePhone()
        val p =
            RemotePlayer(
                "",
                speaker.snapshots,
                recording,
                elapsedRealtime,
                phone,
                failures::add,
                appScope = commandScope,
            )
        p.play()
        idle()
        p.release()
        phone.joined.complete("phone-1")
        idle()
        assertEquals(listOf("phone-1"), sentTo)
    }

    @Test fun anEntryLeftFromBeforeTheServerDroppedThePhoneIsNotAJoin() {
        val players = mapOf("phone-1" to Player("phone-1"))
        val connected = PlayerState.Connected("phone-1", "Home", ClockQuality.Good, AudioStatus.IDLE)
        assertEquals("phone-1", joinedId(connected, players, gone = emptySet()))
        // After the idle close the server dropped the phone, but no event removed its entry.
        assertNull(joinedId(connected, players, gone = setOf("phone-1")))
        // Connected, but the server has not listed it yet; or not connected at all.
        assertNull(joinedId(connected, emptyMap(), gone = emptySet()))
        assertNull(joinedId(PlayerState.Connecting(1), players, gone = emptySet()))
    }

    @Test fun anEntryTheServerKeptUnavailableIsNotAJoin() {
        val connected = PlayerState.Connected("phone-1", "Home", ClockQuality.Good, AudioStatus.IDLE)
        assertNull(joinedId(connected, mapOf("phone-1" to Player("phone-1", available = false)), gone = emptySet()))
    }

    @Test fun aPhoneTheServerTurnedAwayIsRefusedAtOnce() {
        val unpaired = PlayerState.Failed(FailureCause.Unpaired)
        val refused = runCatching { joinedId(unpaired, emptyMap(), gone = emptySet()) }
        val error = refused.exceptionOrNull() as PhoneNotReadyException
        assertEquals(R.string.error_phone_refused, failureText(error))
    }

    @Test fun aPhoneThatCannotStartItsPlayerIsNotBlamedOnTheServer() {
        val failed = PlayerState.Failed(FailureCause.SetupFailed)
        val error = runCatching { joinedId(failed, emptyMap(), gone = emptySet()) }
            .exceptionOrNull() as PhoneNotReadyException
        assertEquals(R.string.error_phone_not_ready, failureText(error))
        // It too waits for a new connection, which Retry makes.
        assertTrue(error.stopped)
    }

    @Test fun aPhoneThatDoesNotJoinIsReported() {
        val phone = FakePhone()
        val p =
            RemotePlayer(
                "phone-1",
                speaker.snapshots,
                recording,
                elapsedRealtime,
                phone,
                failures::add,
                appScope = commandScope,
            )
        phone.joined.completeExceptionally(PhoneNotReadyException())
        p.play()
        idle()
        assertEquals(emptyList<String>(), sentTo)
        assertTrue(failures.single().error is PhoneNotReadyException)
    }

    @Test fun aCommandThatTimesOutWaitingForTheServerIsReportedNotSwallowed() {
        var attempts = 0
        val unreachable = object : SpeakerCommands by speaker {
            // MaClient.call gives up waiting for a connection with MaConnectionException.
            override suspend fun next(playerId: String) {
                attempts++
                throw MaConnectionException("Not connected")
            }
        }
        val p =
            RemotePlayer(
                "kitchen",
                speaker.snapshots,
                unreachable,
                elapsedRealtime,
                local = null,
                onCommandFailed = failures::add,
                appScope = commandScope,
            )
        p.seekToNext()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertEquals(1, failures.size)

        failures.single().retry?.invoke()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertEquals(2, attempts)
    }

    @Test fun sendspinKeepsItsServerWhileTheControlSocketReconnects() {
        val server = ServerInfo("s1", "2.11.0", 99)
        val connected = ConnectionState.Connected(server, null)
        assertEquals(server, sendspinServer(wanted = true, connected, last = null, retriesSafe = true))
        // A Wi-Fi blip, a missed ping or a token rotation: Sendspin reconnects by itself.
        assertEquals(server, sendspinServer(true, ConnectionState.Connecting, last = server, retriesSafe = true))
        assertEquals(server, sendspinServer(true, ConnectionState.Offline, last = server, retriesSafe = true))
        // The idle close, signing out, or another speaker picked: stop.
        assertNull(sendspinServer(true, ConnectionState.Idle, last = server, retriesSafe = true))
        assertNull(sendspinServer(true, ConnectionState.SignedOut, last = server, retriesSafe = true))
        assertNull(sendspinServer(false, connected, last = server, retriesSafe = true))
        // Never connected yet: no server to keep.
        assertNull(sendspinServer(true, ConnectionState.Connecting, last = null, retriesSafe = true))
    }

    @Test fun sendspinWaitsForTheControlSocketWhereItsRetriesAreNotSafe() {
        val server = ServerInfo("s1", "2.11.0", 99)
        // Plain http off the network where the server answered: the token would go to whoever answers there.
        assertNull(sendspinServer(true, ConnectionState.Offline, last = server, retriesSafe = false))
        assertNull(sendspinServer(true, ConnectionState.Connecting, last = server, retriesSafe = false))
        // A socket that connected there checked the server first; one still up from before goes on.
        val connected = ConnectionState.Connected(server, null)
        assertEquals(server, sendspinServer(true, connected, last = null, retriesSafe = false))
    }

    @Test fun theLocksFollowTheAudioNotTheConnection() {
        fun audio(phase: AudioPhase, starved: Boolean = false) = AudioStatus(phase, bufferedMs = 0, starved = starved)
        val playing = PlayerState.Connected("phone-1", "Home", ClockQuality.Good, audio(AudioPhase.Playing))
        assertTrue(awakeFor(playing, was = false))
        assertFalse(awakeFor(playing.copy(audio = AudioStatus.IDLE), was = true))
        // The connection drops: awake while the buffer still plays, not once it is starved.
        assertTrue(awakeFor(PlayerState.Reconnecting(1, null, audio(AudioPhase.Playing)), was = true))
        assertFalse(
            awakeFor(PlayerState.Reconnecting(1, null, audio(AudioPhase.Playing, starved = true)), true),
        )
        assertTrue(awakeFor(PlayerState.Connecting(2), was = true))
        assertFalse(awakeFor(PlayerState.Disabled, was = true))
    }
}
