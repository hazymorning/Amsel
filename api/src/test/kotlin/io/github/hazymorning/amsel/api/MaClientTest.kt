package io.github.hazymorning.amsel.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        block: suspend (MaClient, FakeMaServer) -> Unit,
    ) = runBlocking {
        val fake = FakeMaServer(answering) { command, _ ->
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
}
