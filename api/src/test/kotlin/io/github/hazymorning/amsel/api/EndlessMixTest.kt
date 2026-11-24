package io.github.hazymorning.amsel.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EndlessMixTest {
    private val versions = listOf("2.10", "2.11")
    private val seed = "library://album/3"

    private fun sample(version: String, songs: Int): JsonObject {
        val recorded = FakeMaServer.recorded(version, "music_playlists_playlist_tracks").getValue("result").jsonArray
        return FakeMaServer.result(JsonArray(recorded.take(songs)))
    }

    private fun withMix(
        version: String,
        songs: Int,
        answers: (command: String, asked: Int) -> JsonObject? = { _, _ -> null },
        block: suspend (MaApi, FakeMaServer) -> Unit,
    ) {
        val asked = mutableMapOf<String, Int>()
        withFakeApi(
            version,
            { command, _ ->
                val nth = asked.merge(command, 1, Int::plus) ?: 1
                val sampled = sample(version, songs).takeIf { command == SAMPLE }
                listOf(answers(command, nth) ?: sampled ?: FakeMaServer.result(null))
            },
            block,
        )
    }

    private fun FakeMaServer.sent(): List<String> =
        received.map { it.getValue("command").jsonPrimitive.content }.filter { it != "auth" }

    @Test fun `the mix is sampled, repeat goes off, then it replaces what plays`() = versions.forEach { v ->
        withMix(v, songs = 3) { api, server ->
            api.playEndlessMix("kitchen", seed)
            assertEquals(listOf(SAMPLE, REPEAT, PLAY), server.sent())
            val sampled = server.args(SAMPLE)
            assertEquals(seed, sampled.getValue("item_id").jsonPrimitive.content)
            assertEquals("radio_playlist", sampled.getValue("provider_instance_id_or_domain").jsonPrimitive.content)
            assertEquals("off", server.args(REPEAT).getValue("repeat_mode").jsonPrimitive.content)
            val play = server.args(PLAY)
            val media = play.getValue("media").jsonArray.single().jsonPrimitive.content
            assertEquals("radio_playlist://playlist/$seed", media)
            assertEquals("replace", play.getValue("option").jsonPrimitive.content)
            assertEquals(null, play["radio_mode"])
        }
    }

    @Test fun `a seed without songs, or one song with nothing like it, starts nothing`() = versions.forEach { v ->
        listOf(0, 1).forEach { songs ->
            withMix(v, songs) { api, server ->
                assertThrows(NothingToMixException::class.java) { runBlocking { api.playEndlessMix("kitchen", seed) } }
                assertEquals(listOf(SAMPLE), server.sent())
            }
        }
    }

    @Test fun `the same song twice is still one song`() {
        val twice = sample("2.11", 1).getValue("result").jsonArray.let { FakeMaServer.result(JsonArray(it + it)) }
        withMix("2.11", 0, { command, _ -> twice.takeIf { command == SAMPLE } }) { api, _ ->
            assertThrows(NothingToMixException::class.java) { runBlocking { api.playEndlessMix("kitchen", seed) } }
        }
    }

    @Test fun `a mix that holds repeat locked is cleared first`() {
        val locked = FakeMaServer.error(MaCommandException.ERROR_INVALID_COMMAND, "Invalid or unsupported command.")
        withMix("2.11", 3, { command, nth -> locked.takeIf { command == REPEAT && nth == 1 } }) { api, server ->
            api.playEndlessMix("kitchen", seed)
            assertEquals(listOf(SAMPLE, REPEAT, "player_queues/clear", REPEAT, PLAY), server.sent())
        }
    }

    @Test fun `a sample that takes long means there are songs, and the mix starts`() {
        val slow = { command: String, _: Int -> null.also { if (command == SAMPLE) Thread.sleep(6_000) } }
        withMix("2.11", 0, slow) { api, server ->
            api.playEndlessMix("kitchen", seed)
            assertEquals(listOf(SAMPLE, REPEAT, PLAY), server.sent())
        }
    }

    @Test fun `songs that will not play after all leave no dead mix behind`() {
        val nothing = FakeMaServer.error(2, "There is nothing to play here.")
        val emptied = FakeMaServer.recorded("2.11", "player_queues_get__endless_mix").getValue("result").jsonObject
            .let { JsonObject(it + ("items" to JsonPrimitive(0)) + ("queue_id" to JsonPrimitive("kitchen"))) }
        val answers = { command: String, _: Int ->
            when (command) {
                PLAY -> nothing
                "player_queues/all" -> FakeMaServer.result(JsonArray(listOf(emptied)))
                else -> null
            }
        }
        withMix("2.11", 3, answers) { api, server ->
            assertThrows(NothingToMixException::class.java) { runBlocking { api.playEndlessMix("kitchen", seed) } }
            assertEquals(listOf(SAMPLE, REPEAT, PLAY, "player_queues/all", "player_queues/clear"), server.sent())
        }
    }

    @Test fun `a queue that kept its songs is not cleared`() {
        val nothing = FakeMaServer.error(2, "There is nothing to play here.")
        withMix("2.11", 3, { command, _ -> nothing.takeIf { command == PLAY } }) { api, server ->
            assertThrows(NothingToMixException::class.java) { runBlocking { api.playEndlessMix("kitchen", seed) } }
            assertEquals(listOf(SAMPLE, REPEAT, PLAY, "player_queues/all"), server.sent())
        }
    }

    @Test fun `a speaker that is gone says so and gets no mix`() {
        val gone = FakeMaServer.error(10, "The player is not available.")
        withMix("2.11", 3, { command, _ -> gone.takeIf { command == REPEAT } }) { api, server ->
            val mix = { runBlocking { api.playEndlessMix("kitchen", seed) } }
            assertEquals(10, assertThrows(MaCommandException::class.java, mix).code)
            assertEquals(listOf(SAMPLE, REPEAT), server.sent())
        }
    }
}

private const val SAMPLE = "music/playlists/playlist_tracks"
private const val REPEAT = "player_queues/repeat"
private const val PLAY = "player_queues/play_media"
