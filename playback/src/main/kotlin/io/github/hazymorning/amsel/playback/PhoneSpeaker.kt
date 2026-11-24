package io.github.hazymorning.amsel.playback

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.WakeLockManager
import androidx.media3.common.util.WifiLockManager
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaException
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
 * Transport goes through the Music Assistant API, as for any speaker, never to the Sendspin module;
 * audio focus, becoming-noisy and calls are handled in the module's AudioTrackSink.
 */
@OptIn(UnstableApi::class)
class PhoneSpeaker(
    context: Context,
    private val data: DataGraph,
    okHttp: OkHttpClient,
    private val versionName: String,
    debug: Boolean,
    /** The app's scope: the phone stays a speaker for as long as the app runs. */
    appScope: CoroutineScope,
) : LocalOutput {
    private val app = context.applicationContext
    private val scope = appScope + Dispatchers.IO
    private val config = MutableStateFlow<LocalPlayerConfig?>(null)
    private val wanted = MutableStateFlow(false)

    private val keptBy = MutableStateFlow(0)

    private val sendspin = MutableStateFlow<PlayerState>(PlayerState.Disabled)

    // Taking and releasing a lock is a binder call that may block, so not on the main thread.
    private val lockLooper = HandlerThread("Amsel:locks").apply { start() }.looper
    private val wakeLock = WakeLockManager(app, lockLooper, Clock.DEFAULT)
    private val wifiLock = WifiLockManager(app, lockLooper, Clock.DEFAULT)
    private val _playerId = MutableStateFlow<String?>(null)
    private var pausedByInterruption = false
    private val audio = app.getSystemService(AudioManager::class.java)
    private var heldForCall = false

    // Android 12+: the call's end releases the hold even when the music does not resume.
    private val callEnd: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AudioManager.OnModeChangedListener { mode -> if (mode == AudioManager.MODE_NORMAL) holdForCall(false) }
    } else {
        null
    }

    /** Null until the phone's first join; persisted across runs. */
    val playerId: StateFlow<String?> = _playerId.asStateFlow()

    private val _audible = MutableStateFlow(false)

    val audible: StateFlow<Boolean> = _audible.asStateFlow()

    /** Over TLS the token reaches only the server; over plain http, only on the network checked last. */
    private val retriesSafe = combine(data.session.address, data.session.checkedHere) { address, here ->
        here || address?.baseUrl?.isHttps == true
    }

    /** The server drops the phone's queue when it leaves, so it stays connected while that queue holds music. */
    private val hasMusic = combine(_playerId, data.speakers.players, data.speakers.gone) { id, players, gone ->
        id !in gone && players[id]?.hasMusic == true
    }

    private val _failures =
        MutableSharedFlow<CommandFailure>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

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
                // The same address and token give an equal config, so a control-socket reconnect leaves
                // Sendspin's own connection alone.
                .map { server -> server?.let(::configFor) }
                .distinctUntilChanged()
                .collect { config.value = it }
        }
    }

    fun setWanted(value: Boolean) {
        wanted.value = value
        if (value) startObserving() else holdForCall(false)
    }

    /**
     * Even if another speaker is picked meanwhile: moving the music away reads the phone's queue on the
     * server, which drops that queue the moment the phone's Sendspin client disconnects.
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

    /** For a Retry after the client stopped for good: it reconnects only on a config other than its last. */
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
                        // Kept in memory for this run; the next join stores it again.
                    }
                }
                val awake = awakeFor(state, _audible.value)
                wakeLock.setStayAwake(awake)
                wifiLock.setStayAwake(awake)
                _audible.value = awake
                // sendspin changes after playerId, so whoever awaited the join reads the id it joined with.
                val was = (sendspin.value as? PlayerState.Connected)?.playerId
                // Each join makes the server rebuild the phone's queue without its time; noted before
                // sendspin changes, so a command waiting for the join finds where the queue was.
                when {
                    was != null && state !is PlayerState.Connected -> data.speakers.left(was)
                    was == null && state is PlayerState.Connected -> data.speakers.rejoined(state.playerId)
                }
                sendspin.value = state
                // Disabled on purpose, the module says goodbye with user_request and the server drops
                // the phone at once; after the idle close no event reports that, and the stale entry
                // must not pass for a new join.
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
     * A call can outlast the session's idle close, which would drop the phone and its queue from the
     * server so the music could not resume: the session stays open until the call ends.
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

    private suspend fun localPause(id: String) {
        player.hold(true)
        tell(id) { pause(it) }
    }

    private suspend fun tell(id: String, command: suspend MaApi.(String) -> Unit) {
        val error = try {
            data.session.api.command(id)
            return
        } catch (e: MaException) {
            e
        }
        _failures.tryEmit(CommandFailure(id, error) { scope.launch { tell(id, command) } })
    }

    private fun configFor(server: ServerInfo): LocalPlayerConfig? {
        val (address, token) = data.session.credentials() ?: return null
        val metered = app.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
        return sendspinConfig(address, token, server, deviceName(app), metered)
    }

    companion object {
        private const val CONNECT_WAIT_MS = 15_000L

        private val CALL_MODES =
            setOf(AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)
    }
}

interface LocalOutput {
    val id: String?

    /** Silences the output at once, ahead of the server's pause; false releases it. */
    fun hold(held: Boolean)

    /**
     * The player id once the server lists the joined phone, opening the control socket if needed.
     * Throws [PhoneNotReadyException] after 15 s, or at once when the client stopped for good.
     */
    suspend fun awaitConnected(): String
}
