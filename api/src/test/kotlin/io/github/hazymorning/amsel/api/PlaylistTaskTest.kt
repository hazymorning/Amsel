package io.github.hazymorning.amsel.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistTaskTest {
    private fun task(status: String, error: String? = null) = buildJsonObject {
        put("id", "t1")
        put("status", status)
        error?.let { put("last_error", it) }
    }

    private fun tasksUpdated(status: String, error: String? = null) = buildJsonObject {
        put("event", "tasks_updated")
        put(
            "data",
            buildJsonArray {
                addJsonObject {
                    put("id", "other")
                    put("status", "running")
                }
                add(task(status, error))
            },
        )
    }

    private fun withEdit(finalStatus: String, error: String? = null, block: suspend (MaApi) -> Unit) {
        lateinit var server: FakeMaServer
        withFakeApi("2.11", { command, _ ->
            if (command == "music/playlists/remove_playlist_tracks") {
                // The finished task can arrive before the answer that names it.
                server.push(tasksUpdated(finalStatus, error))
                listOf(FakeMaServer.result(task("pending")))
            } else {
                listOf(FakeMaServer.result(null))
            }
        }) { api, fake ->
            server = fake
            block(api)
        }
    }

    @Test fun `a removal returns once its task succeeded`() = withEdit("success") { api ->
        withTimeout(5000) { api.removeFromPlaylist("7", listOf(3)) }
    }

    @Test fun `a task that failed is a failure`() = withEdit("failed", "Position out of range") { api ->
        val failure = assertThrows(MaCommandException::class.java) {
            runBlocking { api.removeFromPlaylist("7", listOf(30)) }
        }
        assertEquals("Position out of range", failure.details)
    }

    @Test fun `a connection that closes before the task finishes leaves it unknown`() {
        val sent = CompletableDeferred<Unit>()
        withFakeApi("2.11", { command, _ ->
            if (command == "music/playlists/remove_playlist_tracks") sent.complete(Unit)
            listOf(FakeMaServer.result(task("pending")))
        }) { api, fake ->
            val removal = coroutineScope {
                val waiting = async { runCatching { api.removeFromPlaylist("7", listOf(3)) } }
                sent.await()
                fake.dropConnections()
                withTimeout(5000) { waiting.await() }
            }
            assertTrue(removal.exceptionOrNull() is MaConnectionException)
        }
    }
}
