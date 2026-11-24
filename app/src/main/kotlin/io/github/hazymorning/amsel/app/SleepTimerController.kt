package io.github.hazymorning.amsel.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.membersOf
import io.github.hazymorning.amsel.api.orNullOnFailure
import io.github.hazymorning.amsel.data.DataGraph
import io.github.hazymorning.amsel.data.Prefs
import io.github.hazymorning.amsel.playback.LocalPlayback
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.positionAt
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
sealed interface SleepMode {
    data class Minutes(val minutes: Int) : SleepMode

    /** The end of the chapter that plays, or of the episode or book without chapters. */
    data object EndOfItem : SleepMode
}

/**
 * [mode] is null for a timer set elsewhere. [endsAtMs] is `System.currentTimeMillis` time, null
 * while an end-of-item timer waits for the music to play again.
 */
@Immutable
data class SleepTimerState(val mode: SleepMode?, val endsAtMs: Long?)

// Kept by the server where it has them, so they stop the music with the phone asleep or the app gone;
// otherwise this phone pauses the speaker while the app runs (docs/API-NOTES.md, "Player commands").
class SleepTimerController(
    context: Context,
    private val data: DataGraph,
    private val playing: PlaybackState,
    private val local: LocalPlayback,
    private val messages: MessageController,
    private val scope: CoroutineScope,
) {
    private val mine = MutableStateFlow<Map<String, SleepTimerState>>(emptyMap())
    private val followers = ConcurrentHashMap<String, Job>()

    private val serverBacked = ConcurrentHashMap.newKeySet<String>()

    // The server's report may lag a moment behind a timer set here.
    private val setAt = ConcurrentHashMap<String, Long>()

    private val started = AtomicBoolean(false)

    val timers: StateFlow<Map<String, SleepTimerState>> =
        combine(mine, data.speakers.players) { local, players ->
            val server = players.mapNotNull { (id, player) ->
                player.sleepTimerExpiresAt?.let { id to SleepTimerState(null, data.speakers.phoneMillis(it)) }
            }.toMap()
            server + local
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val sensors = context.getSystemService(SensorManager::class.java)
    private var listening = false
    private val shake = ShakeListener { onShake() }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // A timer the server ended, or one cleared elsewhere, ends here too.
            data.speakers.players.collect { players ->
                val now = System.currentTimeMillis()
                mine.value.forEach { (id, timer) ->
                    val recent = now - (setAt[id] ?: 0L) < SERVER_LAG_MS
                    val gone = players[id]?.sleepTimerExpiresAt == null
                    val before = timer.endsAtMs != null && now < timer.endsAtMs - SERVER_LAG_MS
                    if (gone && !recent && before && id in serverBacked) drop(id)
                }
            }
        }
        scope.launch {
            combine(
                mine.map { timers -> timers.values.any { it.mode is SleepMode.Minutes } }.distinctUntilChanged(),
                data.prefs.prefs.map { (it ?: Prefs()).sleepShake }.distinctUntilChanged(),
            ) { running, shakes -> running && shakes }
                .collect(::listenForShakes)
        }
        scope.launch { followPlayback() }
    }

    fun set(speakerId: String, mode: SleepMode) {
        if (mode is SleepMode.Minutes) remember(mode.minutes)
        val seconds = when (mode) {
            is SleepMode.Minutes -> mode.minutes * SECONDS_PER_MINUTE
            SleepMode.EndOfItem -> itemLeftSec() ?: return
        }
        begin(speakerId, mode, seconds)
    }

    fun clear(speakerId: String) {
        drop(speakerId)
        scope.launch {
            try {
                data.session.api.clearSleepTimer(speakerId)
            } catch (e: MaCommandException) {
                // An older server has no sleep timer.
            } catch (e: MaConnectionException) {
                messages.show(R.string.sleep_failed, retry = { clear(speakerId) })
            }
        }
    }

    // A start counts only against the speaker's state when last seen in a loaded list, so music
    // that already played when the app opened is not one.
    private suspend fun followPlayback() {
        var seen: Pair<String, Boolean>? = null
        combine(playing.nowPlaying, data.speakers.players, ::Pair).collect { (now, players) ->
            val id = playing.speaker.value.id
            if (id !in players && !playing.playsDownload(id)) {
                seen = null
                return@collect
            }
            val isPlaying = now?.isPlaying == true
            val startedNow = isPlaying && seen == (id to false)
            seen = id to isPlaying
            val prefs = data.prefs.prefs.value
            val free = mine.value[id] == null && players[id]?.sleepTimerExpiresAt == null
            if (startedNow && free && prefs?.sleepOnPlay == true) set(id, SleepMode.Minutes(prefs.sleepMinutes))
            if (now != null) followItem(id, now)
        }
    }

    private fun remember(minutes: Int) {
        scope.launch {
            try {
                data.prefs.setSleepMinutes(minutes)
            } catch (e: IOException) {
                // Only the length offered next time is lost.
            }
        }
    }

    private fun drop(speakerId: String) {
        serverBacked.remove(speakerId)
        followers.remove(speakerId)?.cancel()
        mine.update { it - speakerId }
    }

    private fun begin(speakerId: String, mode: SleepMode, seconds: Int) {
        val endsAt = System.currentTimeMillis() + seconds * MS
        setAt[speakerId] = System.currentTimeMillis()
        mine.update { it + (speakerId to SleepTimerState(mode, endsAt)) }
        followers.remove(speakerId)?.cancel()
        followers[speakerId] = scope.launch {
            // A download playing here is this phone's alone: the server would time the silent stream.
            val onServer = !playing.playsDownload(speakerId) && orNullOnFailure {
                data.session.api.setSleepTimer(speakerId, seconds)
                true
            } ?: false
            if (onServer) serverBacked.add(speakerId) else serverBacked.remove(speakerId)
            countDown(speakerId, endsAt, onServer)
        }
    }

    private suspend fun countDown(speakerId: String, endsAt: Long, onServer: Boolean) {
        val fadeMs = (data.prefs.prefs.value ?: Prefs()).sleepFadeSec * MS
        delay((endsAt - fadeMs - System.currentTimeMillis()).coerceAtLeast(0))
        if (playing.playsDownload(
                speakerId,
            )
        ) {
            fadeHere(endsAt, fadeMs)
        } else {
            fadeThere(speakerId, endsAt, fadeMs, onServer)
        }
        mine.update { timers -> timers.filterNot { (id, t) -> id == speakerId && t.endsAtMs == endsAt } }
        coroutineContext[Job]?.let { followers.remove(speakerId, it) }
        serverBacked.remove(speakerId)
    }

    private suspend fun fadeHere(endsAt: Long, fadeMs: Long) = withContext(Dispatchers.Main) {
        try {
            while (fadeMs > 0) {
                val left = (endsAt - System.currentTimeMillis()).coerceAtLeast(0)
                local.setVolume(left.toFloat() / fadeMs)
                if (left == 0L) break
                delay(FADE_STEP_MS)
            }
            delay((endsAt - System.currentTimeMillis()).coerceAtLeast(0))
            local.pause()
        } finally {
            local.setVolume(1f)
        }
    }

    private suspend fun fadeThere(speakerId: String, endsAt: Long, fadeMs: Long, onServer: Boolean) {
        val api = data.session.api
        val players = data.speakers.players.value
        val from = players[speakerId]?.takeIf { it.hasVolume && fadeMs > 0 }?.volume
        val members = (listOfNotNull(players[speakerId]) + players.membersOf(speakerId)).mapNotNull { player ->
            player.volumeLevel?.let { player.playerId to it }
        }
        try {
            if (from != null) {
                while (true) {
                    val left = (endsAt - System.currentTimeMillis()).coerceAtLeast(0)
                    // A step the speaker misses ends neither the fade nor the timer.
                    orNullOnFailure { api.setVolume(speakerId, (from * left / fadeMs).toInt()) }
                    if (left == 0L) break
                    delay(FADE_STEP_MS)
                }
            }
            delay((endsAt - System.currentTimeMillis()).coerceAtLeast(0))
            if (!onServer) orNullOnFailure { api.pause(speakerId) }
            delay(RESTORE_AFTER_MS)
        } finally {
            if (from != null) {
                withContext(NonCancellable) {
                    // A group fades as one but comes back member by member: scaled down to nearly
                    // nothing, its balance would be lost.
                    if (members.size > 1) {
                        members.forEach { (id, level) -> orNullOnFailure { api.setOwnVolume(id, level) } }
                    } else {
                        orNullOnFailure { api.setVolume(speakerId, from) }
                    }
                }
            }
        }
    }

    private fun followItem(speakerId: String, now: NowPlayingUi) {
        val timer = mine.value[speakerId] ?: return
        if (timer.mode != SleepMode.EndOfItem) return
        if (!now.isPlaying) {
            if (timer.endsAtMs != null) {
                followers.remove(speakerId)?.cancel()
                mine.update { it + (speakerId to SleepTimerState(SleepMode.EndOfItem, null)) }
                // An older server has no timer to clear; on a paused speaker it would stop nothing.
                scope.launch { orNullOnFailure { data.session.api.clearSleepTimer(speakerId) } }
            }
            return
        }
        val left = itemLeftSec(now) ?: return
        val expected = System.currentTimeMillis() + left * MS
        if (timer.endsAtMs == null || abs(expected - timer.endsAtMs) > DRIFT_MS) {
            begin(speakerId, SleepMode.EndOfItem, left)
        }
    }

    private fun itemLeftSec(now: NowPlayingUi? = playing.nowPlaying.value): Int? {
        val state = now?.takeIf { it.spoken } ?: return null
        val position = state.positionAt(SystemClock.elapsedRealtime())
        val end = if (state.hasChapters) {
            state.chapters.lastOrNull { it.startMs <= position }?.endMs ?: state.durationMs
        } else {
            state.durationMs
        }
        val left = ((end - position) / state.speed.coerceAtLeast(MIN_SPEED)).toLong() / MS
        return left.toInt().takeIf { it > 0 }
    }

    private fun onShake() {
        mine.value.forEach { (id, timer) ->
            val mode = timer.mode
            if (mode is SleepMode.Minutes) set(id, mode)
        }
    }

    private fun listenForShakes(wanted: Boolean) {
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        if (wanted && !listening) {
            listening = sensors.registerListener(shake, sensor, SensorManager.SENSOR_DELAY_UI)
        } else if (!wanted && listening) {
            sensors.unregisterListener(shake)
            listening = false
        }
    }

    private companion object {
        const val MS = 1000L
        const val SECONDS_PER_MINUTE = 60
        const val FADE_STEP_MS = 500L
        const val RESTORE_AFTER_MS = 3_000L
        const val SERVER_LAG_MS = 10_000L
        const val DRIFT_MS = 4_000L
        const val MIN_SPEED = 0.25f
    }
}
