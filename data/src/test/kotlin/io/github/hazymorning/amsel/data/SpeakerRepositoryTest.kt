package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.MaEvent
import io.github.hazymorning.amsel.api.MaJson
import io.github.hazymorning.amsel.api.PlayerMedia
import io.github.hazymorning.amsel.api.fixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerRepositoryTest {
    private var now = 1_000L

    private fun event(version: String, name: String): MaEvent {
        val r = fixture(version, name)["response"]!!.jsonObject
        return MaEvent(r["event"]!!.jsonPrimitive.content, r["object_id"]?.jsonPrimitive?.content, r["data"])
    }

    @Test fun `refresh loads speakers and queues`() = withRecordedServer { api, _ ->
        val speakers = SpeakerRepository(api, { now })
        speakers.refresh()
        assertTrue(speakers.players.value.values.any { it.shownName == "Kitchen" })
        assertTrue(speakers.queues.value.isNotEmpty())
    }

    @Test fun `an event heard while the lists were on their way is not undone by them`() {
        lateinit var speakers: SpeakerRepository
        val renamed = event("2.10", "event_player_updated").let { e ->
            val data = JsonObject(e.data!!.jsonObject + ("name" to JsonPrimitive("Kitchen corner")))
            e.copy(data = JsonObject(data + ("display_name" to JsonPrimitive("Kitchen corner"))))
        }
        val renameDuringReload = { command: String, _: JsonObject? ->
            if (command == "players/all") speakers.onEvent(renamed)
            null
        }
        withRecordedServer(overrides = renameDuringReload) { api, _ ->
            speakers = SpeakerRepository(api, { now })
            speakers.refresh()
            assertEquals("Kitchen corner", speakers.players.value.getValue(renamed.objectId!!).shownName)
        }
    }

    @Test fun `events update players queues and positions`() = withRecordedServer { api, _ ->
        val speakers = SpeakerRepository(api, { now })
        listOf("2.10", "2.11").forEach { v ->
            speakers.onEvent(event(v, "event_player_updated"))
            speakers.onEvent(event(v, "event_queue_updated"))
        }
        val id = event("2.10", "event_player_updated").objectId!!
        assertTrue(speakers.players.value.containsKey(id))
        speakers.onEvent(MaEvent("queue_time_updated", id, JsonPrimitive(30.0)))
        assertEquals(PositionAnchor(30.0, now), speakers.queues.value.getValue(id).position)
    }

    @Test fun `another source's position runs on from the time the server gave it`() = withRecordedServer { api, _ ->
        val speakers = SpeakerRepository(api, { now })
        val fiveSecondsAgo = System.currentTimeMillis() / 1000.0 - 5
        val media = PlayerMedia(uri = "spotify://track/x", elapsedTime = 30.0, elapsedTimeLastUpdated = fiveSecondsAgo)
        val playing = speakers.position(media, playing = true) ?: error("no position")
        assertEquals(35.0, playing.seconds, 0.5)
        assertEquals(now, playing.atElapsedMs)
        assertEquals(PositionAnchor(30.0, now), speakers.position(media, playing = false))
        assertNull(speakers.position(media.copy(elapsedTime = null), playing = true))
    }

    @Test fun `a speaker dropped while no event could reach us stays shown but is not live until listed again`() =
        withRecordedServer { api, _ ->
            val speakers = SpeakerRepository(api, { now })
            val listed = event("2.11", "event_player_updated")
            val id = listed.objectId!!
            speakers.onEvent(listed)
            // This phone after the idle close: the server dropped it, the entry stays for the notification.
            speakers.markGone(id)
            assertTrue(speakers.players.value.containsKey(id))
            assertEquals(setOf(id), speakers.gone.value)
            // An update is not a re-join (docs/API-NOTES.md, "This phone (Sendspin)")...
            speakers.onEvent(listed)
            assertEquals(setOf(id), speakers.gone.value)
            // ...the server adds it again once it has re-joined.
            speakers.onEvent(MaEvent("player_added", id, listed.data))
            assertEquals(emptySet<String>(), speakers.gone.value)
            // A reload is the server's own list.
            speakers.markGone(id)
            speakers.refresh()
            assertEquals(emptySet<String>(), speakers.gone.value)
        }

    @Test fun `a queue playing an Audiobookshelf episode stays on screen`() = withRecordedServer { api, _ ->
        val speakers = SpeakerRepository(api, { now })
        // Audiobookshelf sets an episode's position to its publish time in epoch milliseconds.
        val episode = """
            {"item_id": "li_7 ep_12", "provider": "audiobookshelf--a1", "name": "Fog Signals",
             "uri": "audiobookshelf--a1://podcast_episode/li_7 ep_12", "media_type": "podcast_episode",
             "position": 1730530800000, "duration": 1805,
             "podcast": {"item_id": "li_7", "provider": "audiobookshelf--a1", "name": "Harbour Talk",
                         "uri": "audiobookshelf--a1://podcast/li_7", "media_type": "podcast"}}
        """.trimIndent()
        val queue = """
            {"queue_id": "kitchen", "state": "playing", "elapsed_time": 12.0,
             "current_item": {"queue_id": "kitchen", "queue_item_id": "x1", "name": "Fog Signals",
                              "media_item": $episode}}
        """.trimIndent()
        speakers.onEvent(MaEvent("queue_updated", "kitchen", MaJson.parseToJsonElement(queue)))
        val current = speakers.queues.value.getValue("kitchen").queue.currentItem?.mediaItem
        assertEquals("Fog Signals", current?.name)
        assertEquals(1_730_530_800_000L, current?.position)
    }

    @Test fun `queue items updated asks for a re-fetch`() = withRecordedServer { api, _ ->
        val speakers = SpeakerRepository(api, { now })
        val e = event("2.10", "event_queue_items_updated")
        val got = mutableListOf<String>()
        val job = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Unconfined,
        ).launchCollect(speakers, got)
        speakers.onEvent(e)
        job.cancel()
        assertEquals(listOf(e.objectId), got)
    }
}

private fun CoroutineScope.launchCollect(s: SpeakerRepository, into: MutableList<String>) =
    launch { s.queueItemsChanged.collect { into += it } }
