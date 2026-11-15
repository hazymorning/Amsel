package io.github.hazymorning.amsel.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/** Where the one connection to Music Assistant stands. */
public sealed interface ConnectionState {
    /** Not wanted right now (app in the background and nothing playing), or not signed in. */
    public data object Idle : ConnectionState

    /** The first attempt after a start or a dropped connection. */
    public data object Connecting : ConnectionState

    public data class Connected(val server: ServerInfo, val user: MaUser?) : ConnectionState

    /** Unreachable; it stays so through the retries until one connects. */
    public data object Offline : ConnectionState

    /** The token was rejected: the user must sign in again. Reconnecting stops. */
    public data object SignedOut : ConnectionState

    /** The server is older than the app supports. Reconnecting stops. */
    public data object Unsupported : ConnectionState
}

/**
 * The app's single, authenticated Music Assistant connection. [start] keeps it open (reconnecting
 * with backoff) until [stop]. Commands wait briefly for a connection; events from every
 * connection arrive in [events]. Knows nothing about screens.
 */
public class MaClient(private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val _events =
        MutableSharedFlow<MaEvent>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Guards loop, socket and every state change: a cancelled loop may still be running.
    private val lock = Any()
    private var loop: Job? = null
    private var socket: MaSocket? = null

    public val state: StateFlow<ConnectionState> = _state.asStateFlow()
    public val events: SharedFlow<MaEvent> = _events.asSharedFlow()

    /** Connects to [address] with the long-lived [token], at once, replacing any connection. */
    public fun start(address: ServerAddress, token: String): Unit = synchronized(lock) {
        end()
        _state.value = ConnectionState.Connecting
        loop = scope.launch { runLoop(address, token) }
    }

    /** Closes the connection and stops reconnecting. */
    public fun stop(): Unit = synchronized(lock) {
        end()
        _state.value = ConnectionState.Idle
    }

    /** The connected server, waiting for the connection as [call] does. */
    public suspend fun server(): ServerInfo = connected().server

    /**
     * Sends a command, waiting up to 10 s for a connection first. Throws [MaConnectionException]
     * when there is none or no answer comes, and [MaCommandException] for a server error.
     */
    internal suspend fun call(
        command: String,
        args: JsonObject? = null,
        timeout: Duration = MaSocket.CALL_TIMEOUT,
    ): JsonElement? = callOnConnection(command, args, timeout).first

    /** As [call], with what completes once the connection that carried it closes. */
    internal suspend fun callOnConnection(
        command: String,
        args: JsonObject? = null,
        timeout: Duration = MaSocket.CALL_TIMEOUT,
    ): Pair<JsonElement?, Deferred<Unit>> {
        connected()
        val s = synchronized(lock) { socket } ?: throw MaConnectionException("Not connected")
        return s.call(command, args, timeout) to s.closed
    }

    private suspend fun connected(): ConnectionState.Connected = withTimeoutOrNull(CONNECT_WAIT) {
        state.first {
            it is ConnectionState.Connected || it == ConnectionState.SignedOut || it == ConnectionState.Unsupported
        }
    } as? ConnectionState.Connected ?: throw MaConnectionException("Not connected")

    private fun end() {
        loop?.cancel()
        loop = null
        socket?.close()
        socket = null
    }

    private suspend fun runLoop(address: ServerAddress, token: String) {
        var failures = 0
        while (true) {
            val s = MaSocket(http, address) { _events.tryEmit(it) }
            try {
                val info = s.open()
                if (!Schema.isSupported(info.schemaVersion)) return publish(ConnectionState.Unsupported)
                val user = authenticate(s, token)
                failures = 0
                publish(ConnectionState.Connected(info, user), s)
                s.closed.await()
                publish(ConnectionState.Connecting)
                continue
            } catch (e: MaCommandException) {
                // A server set up anew has no users: like a refused token, no retry can sign in.
                if (e.isAuthError || e.isSetupRequired) return publish(ConnectionState.SignedOut)
            } catch (e: MaConnectionException) {
                // Retried below.
            } finally {
                s.close()
            }
            failures++
            publish(ConnectionState.Offline)
            delay(backoffSeconds(failures) * 1000L)
        }
    }

    /** Only the current loop changes the state; one that start or stop replaced is cancelled here. */
    private suspend fun publish(next: ConnectionState, connected: MaSocket? = null) {
        val self = currentCoroutineContext().job
        synchronized(lock) {
            if (loop !== self) throw CancellationException("Replaced by a newer start or stop")
            socket = connected
            _state.value = next
        }
    }

    private companion object {
        val CONNECT_WAIT = 10.seconds

        /** 1, 2, 4, 8, 16, then every 30 seconds. */
        fun backoffSeconds(failures: Int): Long = if (failures > 5) 30 else 1L shl (failures - 1)
    }
}

internal suspend fun authenticate(socket: MaSocket, token: String): MaUser? {
    val result = socket.call("auth", buildJsonObject { put("token", token) }) as? JsonObject
    return result?.get("user")?.let { MaJson.decodeFromJsonElement(MaUser.serializer(), it) }
}
