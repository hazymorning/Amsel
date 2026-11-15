package io.github.hazymorning.amsel.playback

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.WakeLockManager
import androidx.media3.common.util.WifiLockManager
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.data.DataGraph
import io.music_assistant.sendspin.api.LocalPlayerConfig
import io.music_assistant.sendspin.api.PlayerEvent
import io.music_assistant.sendspin.api.PlayerState
import io.music_assistant.sendspin.api.SendspinPlayer
import io.music_assistant.sendspin.api.StopCause
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * "This phone" as a Music Assistant speaker: the vendored Sendspin player plays the audio the
 * server streams. It connects while the phone is the selected speaker, while its music plays or
 * waits paused (choosing another speaker leaves it playing), or while work still needs it on the
 * server ([keepConnectedDuring]), and keeps its server while the control socket reconnects, where
 * its own retries reach no one else ([sendspinServer]); otherwise nothing touches the network.
 * Transport never goes to the module: the control plane is the Music Assistant API, as for any
 * speaker.
 *
 * Audio focus, becoming-noisy and call handling live in the module's AudioTrackSink; this class
 * adds the wake and Wi-Fi locks while audio plays, tells the server of the pauses and plays the
 * phone makes by itself (focus lost or regained), and keeps the session open through a call.
 */
@OptIn(UnstableApi::class)
class PhoneSpeaker(
    context: Context,
    private val data: DataGraph,
    okHttp: OkHttpClient,
    private val versionName: String,
    debug: Boolean,
    /** The app's: the phone is a speaker for as long as the app runs. */
    appScope: CoroutineScope,
) : LocalOutput {
    private val app = context.applicationContext
    private val scope = appScope + Dispatchers.IO
    private val config = MutableStateFlow<LocalPlayerConfig?>(null)
    private val wanted = MutableStateFlow(false)

    /** Work that still needs the phone on the server after another speaker was picked. */
    private val keptBy = MutableStateFlow(0)

    /** The Sendspin client's state since the phone was first wanted. */
    private val sendspin = MutableStateFlow<PlayerState>(PlayerState.Disabled)

    // Taking and releasing a lock is a binder call that may block, so not on the main thread.
    private val lockLooper = HandlerThread("Amsel:locks").apply { start() }.looper
    private val wakeLock = WakeLockManager(app, lockLooper, Clock.DEFAULT)
    private val wifiLock = WifiLockManager(app, lockLooper, Clock.DEFAULT)
    private val _playerId = MutableStateFlow<String?>(null)
    private var pausedByInterruption = false
    private val audio = app.getSystemService(AudioManager::class.java)
    private var heldForCall = false

    // From Android 12 the call's end lets the session close even when the music cannot resume.
    private val callEnd: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AudioManager.OnModeChangedListener { mode -> if (mode == AudioManager.MODE_NORMAL) holdForCall(false) }
    } else {
        null
    }

    /** The phone's player id on the server, known after the first connection (persisted). */
    val playerId: StateFlow<String?> = _playerId.asStateFlow()

    private val _audible = MutableStateFlow(false)

    /** Whether its audio plays or is about to, whichever speaker is selected. */
    val audible: StateFlow<Boolean> = _audible.asStateFlow()

    /** Over TLS the token reaches only the server; over plain http, only on the network checked last. */
    private val retriesSafe = combine(data.session.address, data.session.checkedHere) { address, here ->
        here || address?.baseUrl?.isHttps == true
    }

    /** The server drops the phone's queue when it leaves, so it stays while there is music to go on with. */
    private val hasMusic = combine(_playerId, data.speakers.players, data.speakers.gone) { id, players, gone ->
        id !in gone && players[id]?.hasMusic == true
    }

    private val _failures =
        MutableSharedFlow<CommandFailure>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The pauses and plays the phone sends by itself (a call, a held stream) that did not reach the server. */
    val failures: SharedFlow<CommandFailure> = _failures.asSharedFlow()

    private val player: SendspinPlayer by lazy {
        sendspinPlayer(app, okHttp, config, { token ->
            data.session.api.pairWebPlayer(token)
        }, versionName, debug, scope)
    }

    init {
        wakeLock.setEnabled(true)
        wifiLock.setEnabled(true)
        scope.launch {
            data.prefs.prefs.map { it?.phonePlayerId }.distinctUntilChanged().collect { stored ->
                if (stored != null && _playerId.value == null) _playerId.value = stored
            }
        }
        scope.launch {
            combine(
                wanted,
                keptBy,
                hasMusic,
                data.session.client.state,
                retriesSafe,
            ) { want, kept, music, connection, safe ->
                Triple(want || kept > 0 || music, connection, safe)
            }.runningFold(null as ServerInfo?) { last, (want, connection, safe) ->
                sendspinServer(want, connection, last, safe)
            }
                // The same address and token make an equal config, so a reconnect of the control
                // socket leaves Sendspin's own connection alone.
                .map { server -> server?.let(::configFor) }
                .distinctUntilChanged()
                .collect { config.value = it }
        }
    }

    /** True while "This phone" is the selected speaker. */
    fun setWanted(value: Boolean) {
        wanted.value = value
        if (value) startObserving() else holdForCall(false)
    }

    /**
     * Keeps the phone connected while [block] runs, even if another speaker is picked meanwhile:
     * moving the music away from the phone reads the phone's queue on the server, and the server
     * removes that queue the moment the phone's Sendspin client disconnects.
     */
    suspend fun <T> keepConnectedDuring(block: suspend () -> T): T {
        keptBy.update { it + 1 }
        try {
            return block()
        } finally {
            keptBy.update { it - 1 }
        }
    }

    override suspend fun awaitConnected(): String {
        data.session.wake()
        return withTimeoutOrNull(CONNECT_WAIT_MS) {
            combine(sendspin, data.speakers.players, data.speakers.gone, ::joinedId)
                .filterNotNull()
                .first()
        } ?: throw PhoneNotReadyException()
    }

    /**
     * Connects This phone's client afresh, for a Retry after it stopped for good: it waits for a new
     * configuration, and the one it has would not count as new.
     */
    suspend fun reconnect() {
        val current = config.value ?: return
        config.value = null
        sendspin.first { it is PlayerState.Disabled }
        config.value = current
    }

    override val id: String? get() = _playerId.value

    override fun hold(held: Boolean) {
        if (started) player.hold(held)
    }

    @Volatile private var started = false

    private fun startObserving() {
        if (started) return
        started = true
        scope.launch {
            player.state.collect { state ->
                if (state is PlayerState.Connected && _playerId.value != state.playerId) {
                    _playerId.value = state.playerId
                    try {
                        data.prefs.setPhonePlayerId(state.playerId)
                    } catch (e: IOException) {
                        // Held for this run; the next join tells it again.
                    }
                }
                val awake = awakeFor(state, _audible.value)
                wakeLock.setStayAwake(awake)
                wifiLock.setStayAwake(awake)
                _audible.value = awake
                // After playerId, so whoever awaited the connection finds the id it was given.
                val was = (sendspin.value as? PlayerState.Connected)?.playerId
                // Each join makes the server rebuild the phone's queue without its time; before
                // sendspin, so a command waiting for the join finds where the queue was.
                when {
                    was != null && state !is PlayerState.Connected -> data.speakers.left(was)
                    was == null && state is PlayerState.Connected -> data.speakers.rejoined(state.playerId)
                }
                sendspin.value = state
                // Turned off on purpose (another speaker, the idle close, signing out): the module
                // says goodbye with user_request and the server drops the phone at once. After the
                // idle close no event tells us, and the stale entry must not pass for a new join.
                if (state is PlayerState.Disabled && was != null) data.speakers.markGone(was)
            }
        }
        scope.launch {
            player.events.collect { event ->
                val id = _playerId.value ?: return@collect
                when (event) {
                    is PlayerEvent.PlaybackStarted -> {
                        pausedByInterruption = false
                        holdForCall(false)
                    }

                    is PlayerEvent.PlaybackStopped -> when (event.cause) {
                        StopCause.FocusLost -> {
                            pausedByInterruption = true
                            if (audio.mode in CALL_MODES) holdForCall(true)
                            localPause(id)
                        }

                        StopCause.Starved, StopCause.SinkFailed -> localPause(id)

                        else -> Unit
                    }

                    PlayerEvent.FocusRegained -> if (pausedByInterruption) {
                        pausedByInterruption = false
                        holdForCall(false)
                        player.hold(false)
                        tell(id) { play(it) }
                    }

                    is PlayerEvent.StreamStartedWhileHeld -> tell(event.playerId) { pause(it) }

                    // The server's player_added and queue_added already say what changed.
                    PlayerEvent.ServerRefreshNeeded, is PlayerEvent.Warning -> Unit
                }
            }
        }
    }

    /**
     * A call can outlast the session's idle close, which would take the phone and its queue off the
     * server, so the music could not resume after it: the session stays open until the call ends.
     */
    private fun holdForCall(hold: Boolean) {
        synchronized(this) {
            if (heldForCall == hold) return
            heldForCall = hold
            data.session.setInCall(hold)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val listener = callEnd as AudioManager.OnModeChangedListener
                if (hold) {
                    audio.addOnModeChangedListener(app.mainExecutor, listener)
                } else {
                    audio.removeOnModeChangedListener(listener)
                }
            }
        }
    }

    /** Silence at once, then tell the server. */
    private suspend fun localPause(id: String) {
        player.hold(true)
        tell(id) { pause(it) }
    }

    /** Sends [command] for the phone's player [id]; one that does not reach the server is reported with Retry. */
    private suspend fun tell(id: String, command: suspend MaApi.(String) -> Unit) {
        val error = try {
            data.session.api.command(id)
            return
        } catch (e: MaConnectionException) {
            e
        } catch (e: MaCommandException) {
            e
        }
        _failures.tryEmit(CommandFailure(id, error) { scope.launch { tell(id, command) } })
    }

    private fun configFor(server: ServerInfo): LocalPlayerConfig? {
        val (address, token) = data.session.credentials() ?: return null
        return sendspinConfig(address, token, server, deviceName(app))
    }

    companion object {
        /** How long a command for the phone waits for its Sendspin client to join the server. */
        private const val CONNECT_WAIT_MS = 15_000L

        private val CALL_MODES =
            setOf(AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)
    }
}

/** What a player on this phone's own output needs beyond the Music Assistant commands. */
interface LocalOutput {
    /** The phone's player id on the server, once known. */
    val id: String?

    /** Silence the output at once (before the server round trip), or release it. */
    fun hold(held: Boolean)

    /**
     * Waits until this phone's Sendspin client is connected and the server lists its player
     * (opening the control socket if it was closed); the player id. Throws [PhoneNotReadyException]
     * after 15 s, or at once when the server turned the client away for good.
     */
    suspend fun awaitConnected(): String
}
