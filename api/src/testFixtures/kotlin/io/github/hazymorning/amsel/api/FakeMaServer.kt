package io.github.hazymorning.amsel.api

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Loads a recorded fixture: `fixture("2.10", "players_all")`. */
public fun fixture(version: String, name: String): JsonObject {
    val stream = FakeMaServer::class.java.getResourceAsStream("/fixtures/$version/$name.json")
        ?: error("No fixture $version/$name")
    return MaJson.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
}

/** A recorded list result, decoded with [serializer]. */
public fun <T> decodeList(version: String, name: String, serializer: KSerializer<T>): List<T> =
    MaJson.decodeFromJsonElement(
        ListSerializer(serializer),
        fixture(version, name).getValue("response").jsonObject.getValue("result"),
    )

/** A recorded single result, decoded with [serializer]. */
public fun <T> decodeOne(version: String, name: String, serializer: KSerializer<T>): T =
    MaJson.decodeFromJsonElement(serializer, fixture(version, name).getValue("response").jsonObject.getValue("result"))

/**
 * Runs [block] with an [MaApi] signed in to a [FakeMaServer] that sends [version]'s recorded
 * server info; [handler] answers every command but `auth`.
 */
public fun withFakeApi(
    version: String,
    handler: (command: String, args: JsonObject?) -> List<JsonObject>,
    block: suspend (MaApi, FakeMaServer) -> Unit,
): Unit = runBlocking {
    val fake = FakeMaServer(fixture(version, "server_info").getValue("response")) { command, args ->
        if (command == "auth") listOf(FakeMaServer.recorded(version, "auth")) else handler(command, args)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val client = MaClient(OkHttpClient(), scope)
    try {
        client.start(fake.address, "token", fake.serverId)
        withTimeout(5000) { client.state.filterIsInstance<ConnectionState.Connected>().first() }
        block(MaApi(client), fake)
    } finally {
        client.stop()
        scope.cancel()
        fake.close()
    }
}

/**
 * A Music Assistant stand-in on MockWebServer. Sends [serverInfo] on connect and answers each
 * command with [handler]'s frames (the message_id is filled in).
 */
public class FakeMaServer(
    private val serverInfo: JsonElement = fixture("2.10", "server_info")["response"]!!,
    private val handler: (command: String, args: JsonObject?) -> List<JsonObject>,
) : AutoCloseable {
    public val server: MockWebServer = MockWebServer()
    public val received: CopyOnWriteArrayList<JsonObject> = CopyOnWriteArrayList<JsonObject>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()

    init {
        server.start()
        repeat(10) { server.enqueue(MockResponse.Builder().webSocketUpgrade(listener()).build()) }
    }

    public val address: ServerAddress get() = ServerAddress(server.url("/"))

    /** The id its server info names, which a client must expect to send it a token. */
    public val serverId: String get() = serverInfo.jsonObject.getValue("server_id").jsonPrimitive.content

    /** Pushes an event to every connected client. */
    public fun push(event: JsonObject): Unit = sockets.forEach { it.send(event.toString()) }

    /** The arguments of the last [command] received. */
    public fun args(command: String): JsonObject =
        received.last { it["command"]?.jsonPrimitive?.content == command }.getValue("args").jsonObject

    /** Ends every connection abnormally, as a server restart would. */
    public fun dropConnections(): Unit = sockets.forEach { it.close(1011, "restarting") }

    private fun listener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
            webSocket.send(serverInfo.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = MaJson.parseToJsonElement(text).jsonObject
            received += msg
            val id = msg["message_id"]!!.jsonPrimitive.content
            val command = msg["command"]!!.jsonPrimitive.content
            handler(command, msg["args"] as? JsonObject).forEach { frame ->
                webSocket.send(JsonObject(frame + ("message_id" to JsonPrimitive(id))).toString())
            }
        }
    }

    override fun close() {
        sockets.forEach { it.close(1000, null) }
        server.close()
    }

    public companion object {
        public fun result(value: JsonElement?, partial: Boolean = false) = buildJsonObject {
            put("result", value ?: kotlinx.serialization.json.JsonNull)
            put("partial", partial)
        }

        public fun error(code: Int, details: String) = buildJsonObject {
            put("error_code", code)
            put("details", details)
        }

        /** The recorded response of [name] as the reply frame. */
        public fun recorded(version: String, name: String): JsonObject {
            val response = fixture(version, name)["response"]!!.jsonObject
            return JsonObject(response - "message_id")
        }
    }
}
