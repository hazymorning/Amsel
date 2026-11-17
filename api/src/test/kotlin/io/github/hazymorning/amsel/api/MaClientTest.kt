package io.github.hazymorning.amsel.api

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The long-lived token goes only to the server it was given for, whatever answers at its address. */
class MaClientTest {
    private val ours = fixture("2.11", "server_info").getValue("response").jsonObject
    private val ourId = ours.getValue("server_id").jsonPrimitive.content

    /** Another Music Assistant, or anything that says it is one, at the same address. */
    private val stranger = JsonObject(ours + ("server_id" to JsonPrimitive("someone-else")))

    private fun withClient(
        answering: JsonObject,
        sameNetwork: Boolean,
        first: List<JsonObject> = emptyList(),
        block: suspend (MaClient, FakeMaServer) -> Unit,
    ) = runBlocking {
        val fake = FakeMaServer(answering, first) { command, _ ->
            if (command == "auth") listOf(FakeMaServer.recorded("2.11", "auth")) else emptyList()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MaClient(OkHttpClient(), scope) { sameNetwork }
        try {
            withTimeout(10_000) { block(client, fake) }
        } finally {
            client.stop()
            scope.cancel()
            fake.close()
        }
    }

    @Test fun `its own server gets the token and connects`() = withClient(ours, sameNetwork = false) { client, fake ->
        client.start(fake.address, "token", ourId)
        client.state.first { it is ConnectionState.Connected }
        assertEquals(JsonPrimitive("token"), fake.args("auth")["token"])
    }

    @Test fun `another server at the address gets nothing, and the client stays offline`() =
        withClient(stranger, sameNetwork = false) { client, fake ->
            client.start(fake.address, "token", ourId)
            // Three tries, 1 s and 2 s apart: none of them sends the token or gives up.
            while (fake.server.requestCount < 3) delay(50)
            assertEquals(ConnectionState.Offline, client.state.value)
            assertTrue(fake.received.toString(), fake.received.isEmpty())
        }

    @Test fun `another id where the server answered before is the server set up anew`() =
        withClient(stranger, sameNetwork = true) { client, fake ->
            client.start(fake.address, "token", ourId)
            client.state.first { it == ConnectionState.SignedOut }
            assertEquals(2, fake.server.requestCount)
            assertTrue(fake.received.isEmpty())
        }

    @Test fun `another server cannot stop the reconnecting by claiming an old schema`() =
        withClient(JsonObject(stranger + ("schema_version" to JsonPrimitive(1))), sameNetwork = false) { client, fake ->
            client.start(fake.address, "token", ourId)
            while (fake.server.requestCount < 2) delay(50)
            assertEquals(ConnectionState.Offline, client.state.value)
        }

    @Test fun `a refusal before the server names itself never signs out`() =
        withClient(ours, sameNetwork = true, first = listOf(refusal)) { client, fake ->
            client.start(fake.address, "token", ourId)
            // Whatever answers there first refuses: each try ends, and nothing is given up or sent.
            while (fake.server.requestCount < 2) delay(50)
            assertEquals(ConnectionState.Offline, client.state.value)
            assertTrue(fake.received.toString(), fake.received.isEmpty())
        }

    @Test fun `events are heard once the server has named itself, not before`() =
        withClient(ours, sameNetwork = false, first = listOf(event("before"))) { client, fake ->
            val heard = CopyOnWriteArrayList<MaEvent>()
            coroutineScope {
                val listening = launch(Dispatchers.IO) { client.events.collect { heard += it } }
                client.start(fake.address, "token", ourId)
                client.state.first { it is ConnectionState.Connected }
                fake.push(event("after"))
                while (heard.isEmpty()) delay(20)
                listening.cancel()
            }
            assertEquals(listOf("after"), heard.map { it.objectId })
        }

    private val refusal = buildJsonObject {
        put("message_id", "connection")
        put("error_code", MaCommandException.ERROR_INVALID_TOKEN)
        put("details", "Invalid token")
    }

    private fun event(objectId: String) = buildJsonObject {
        put("event", "player_updated")
        put("object_id", objectId)
        put("data", buildJsonObject {})
    }

    @Test fun `a user it cannot read still connects`() = runBlocking {
        // A later server changes its user object: the app connects, knowing less about who it is.
        val oddUser = FakeMaServer.result(buildJsonObject { put("user", buildJsonObject { put("user_id", "u1") }) })
        val fake = FakeMaServer(ours) { command, _ -> if (command == "auth") listOf(oddUser) else emptyList() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MaClient(OkHttpClient(), scope) { false }
        try {
            client.start(fake.address, "token", ourId)
            val connected = withTimeout(10_000) { client.state.first { it is ConnectionState.Connected } }
            assertEquals(null, (connected as ConnectionState.Connected).user)
        } finally {
            client.stop()
            scope.cancel()
            fake.close()
        }
    }
}
