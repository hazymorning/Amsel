package io.github.hazymorning.amsel.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MaSocketTest {
    private val http = OkHttpClient()

    @Test fun `reads server info and answers commands by message id`() = runBlocking {
        FakeMaServer { command, _ ->
            when (command) {
                "players/all" -> listOf(FakeMaServer.recorded("2.10", "players_all"))
                else -> listOf(FakeMaServer.error(12, "Invalid command"))
            }
        }.use { fake ->
            val socket = MaSocket(http, fake.address)
            val info = socket.open()
            assertEquals(65, info.schemaVersion)
            assertEquals("2.10.9", info.serverVersion)
            val players = socket.call("players/all") as JsonArray
            assertTrue(players.isNotEmpty())
            socket.close()
        }
    }

    @Test fun `server errors become MaCommandException`() = runBlocking {
        FakeMaServer { _, _ -> listOf(FakeMaServer.recorded("2.10", "auth__invalid_token")) }.use { fake ->
            val socket = MaSocket(http, fake.address)
            socket.open()
            try {
                socket.call("auth", buildJsonObject { put("token", "x") })
                fail("expected an error")
            } catch (e: MaCommandException) {
                assertEquals(MaCommandException.ERROR_INVALID_TOKEN, e.code)
                assertTrue(e.isAuthError)
            }
            socket.close()
        }
    }

    @Test fun `partial results are joined`() = runBlocking {
        FakeMaServer { _, _ ->
            listOf(
                FakeMaServer.result(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), partial = true),
                FakeMaServer.result(JsonArray(listOf(JsonPrimitive(3))), partial = true),
                FakeMaServer.result(JsonArray(emptyList())),
            )
        }.use { fake ->
            val socket = MaSocket(http, fake.address)
            socket.open()
            val joined = socket.call("music/playlists/playlist_tracks") as JsonArray
            assertEquals(listOf(1, 2, 3), joined.map { it.toString().toInt() })
            socket.close()
        }
    }

    @Test fun `events are published`() = runBlocking {
        FakeMaServer { _, _ -> emptyList() }.use { fake ->
            val received = CompletableDeferred<MaEvent>()
            val socket = MaSocket(http, fake.address) { received.complete(it) }
            socket.open()
            fake.push(fixture("2.10", "event_queue_time_updated")["response"]!!.jsonObject)
            val event = received.await()
            assertEquals("queue_time_updated", event.name)
            assertEquals("updb709e97", event.objectId)
            socket.close()
        }
    }

    @Test fun `pending calls fail when the connection drops`() = runBlocking {
        val asked = CompletableDeferred<Unit>()
        FakeMaServer { _, _ -> emptyList<JsonObject>().also { asked.complete(Unit) } }.use { fake ->
            val socket = MaSocket(http, fake.address)
            socket.open()
            val call = async {
                try {
                    socket.call("players/all")
                    null
                } catch (e: MaConnectionException) {
                    e
                }
            }
            asked.await()
            fake.dropConnections()
            assertTrue(call.await() is MaConnectionException)
        }
    }

    @Test fun `a server without users says so after its info`() = runBlocking {
        FakeMaServer { _, _ -> emptyList() }.use { fake ->
            val socket = MaSocket(http, fake.address)
            socket.open()
            fake.push(
                buildJsonObject {
                    put("message_id", "connection")
                    put("error_code", MaCommandException.ERROR_SETUP_REQUIRED)
                    put("details", "Setup required")
                },
            )
            try {
                socket.call("auth/providers")
                fail("expected an error")
            } catch (e: MaCommandException) {
                assertTrue(e.isSetupRequired)
            }
        }
    }

    @Test fun `a refusal before any server info is no word from the server`() = runBlocking {
        val refusal = buildJsonObject {
            put("message_id", "connection")
            put("error_code", MaCommandException.ERROR_AUTHENTICATION_REQUIRED)
            put("details", "Authentication required")
        }
        FakeMaServer(first = listOf(refusal)) { _, _ -> emptyList() }.use { fake ->
            try {
                MaSocket(http, fake.address).open()
                fail("expected an error")
            } catch (e: MaConnectionException) {
                // Not a MaCommandException: nothing a client would take for a refused token.
            }
        }
    }
}
