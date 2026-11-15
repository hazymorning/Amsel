package io.github.hazymorning.amsel.api

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * One WebSocket connection to Music Assistant, for its whole life. Matches results to commands by
 * `message_id`, joins partial (streamed) results and hands events to [onEvent]. Once [closed]
 * completes the socket is dead; the caller opens a new one.
 */
internal class MaSocket(
    private val http: OkHttpClient,
    private val address: ServerAddress,
    private val onEvent: (MaEvent) -> Unit = {},
) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, PendingCall>()
    private val serverInfo = CompletableDeferred<ServerInfo>()
    private var socket: WebSocket? = null

    @Volatile private var endReason: Exception? = null

    /** Completes when the connection ends. */
    val closed = CompletableDeferred<Unit>()

    /** Opens the socket and waits for the server's first frame. */
    suspend fun open(): ServerInfo {
        socket =
            http.forUrl(address.baseUrl).newWebSocket(Request.Builder().url(address.webSocketUrl).build(), Listener())
        try {
            return withTimeoutOrNull(OPEN_TIMEOUT) { serverInfo.await() }
                ?: throw MaConnectionException("Could not reach $address")
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    /**
     * Sends [command] and waits for its result. Throws [MaCommandException] for a server error and
     * [MaConnectionException] when the connection ends or no answer comes within [timeout].
     */
    suspend fun call(command: String, args: JsonObject? = null, timeout: Duration = CALL_TIMEOUT): JsonElement? {
        val id = nextId.getAndIncrement().toString()
        val call = PendingCall(command)
        pending[id] = call
        try {
            endReason?.let { throw it }
            val frame = buildJsonObject {
                put("message_id", id)
                put("command", command)
                if (args != null) put("args", args)
            }
            val ws = socket ?: throw MaConnectionException("Not connected")
            if (!ws.send(frame.toString())) throw MaConnectionException("Connection closed")
            // A null result is a valid answer, so the wait carries it as JsonNull.
            val answer = withTimeoutOrNull(timeout) { call.result.await() ?: JsonNull }
                ?: throw MaConnectionException("No answer to $command")
            return answer.takeUnless { it is JsonNull }
        } finally {
            pending.remove(id)
        }
    }

    fun close() {
        socket?.close(NORMAL_CLOSURE, null)
        end(MaConnectionException("Connection closed"))
    }

    /** Fails what still waits; [endReason] is set first, so a call made meanwhile sees it. */
    private fun end(reason: Exception) {
        synchronized(this) {
            if (endReason != null) return
            endReason = reason
        }
        closed.complete(Unit)
        serverInfo.completeExceptionally(reason)
        pending.values.forEach { it.result.completeExceptionally(reason) }
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try {
                parseIncoming(text)
            } catch (e: IllegalArgumentException) {
                // Not JSON, or not shaped as a frame (SerializationException is one too).
                Incoming.Unknown
            }
            when (msg) {
                is Incoming.ServerInfoFrame -> serverInfo.complete(msg.info)

                is Incoming.Event -> onEvent(msg.event)

                // Sent right after server_info when the server has no users yet ("Setup required").
                is Incoming.Error -> if (msg.messageId == CONNECTION) {
                    end(MaCommandException(CONNECTION, msg.code, msg.details))
                } else {
                    pending[msg.messageId]?.let {
                        it.result.completeExceptionally(MaCommandException(it.command, msg.code, msg.details))
                    }
                }

                is Incoming.Result -> pending[msg.messageId]?.accept(msg.result, msg.partial)

                Incoming.Unknown -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            end(MaConnectionException("Closed by the server: $code $reason"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            end(MaConnectionException("Connection closed"))

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            end(MaConnectionException("Connection failed", t))
    }

    private class PendingCall(val command: String) {
        val result = CompletableDeferred<JsonElement?>()
        private val parts = mutableListOf<JsonElement>()

        @Synchronized
        fun accept(chunk: JsonElement?, partial: Boolean) {
            if (partial) {
                if (chunk is JsonArray) {
                    parts.addAll(chunk)
                } else if (chunk != null) {
                    parts.add(chunk)
                }
                return
            }
            val final = when {
                parts.isEmpty() -> chunk
                chunk is JsonArray -> JsonArray(parts + chunk)
                chunk == null || chunk is JsonNull -> JsonArray(parts)
                else -> JsonArray(parts + chunk)
            }
            result.complete(final)
        }
    }

    companion object {
        val CALL_TIMEOUT = 30.seconds
        private val OPEN_TIMEOUT = 10.seconds
        private const val NORMAL_CLOSURE = 1000
        private const val CONNECTION = "connection"
    }
}
