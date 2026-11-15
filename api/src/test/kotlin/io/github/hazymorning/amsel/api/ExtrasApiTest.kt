package io.github.hazymorning.amsel.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The endless mix, similar artists and songs, and the AI Radio plugin's DJ, on answers recorded from 2.10.9 and 2.11.0b7. */
class ExtrasApiTest {
    private val versions = listOf("2.10", "2.11")

    @Test fun `similar songs are a music service's own, asked for with a limit alone`() = versions.forEach { v ->
        withFakeApi(v, { _, _ -> listOf(FakeMaServer.recorded(v, "music_tracks_similar_tracks")) }) { api, fake ->
            val songs = api.similarTracks("0_0_0", "test")
            assertEquals(listOf("test://track/0_0_1", "test://track/0_0_2", "test://track/0_0_3"), songs.map { it.uri })
            assertEquals("gangsta rap Test Artist 0", songs.first().artistNames)
            assertEquals("gangsta rap Test Album 0", songs.first().album?.name)
            val args = fake.args("music/tracks/similar_tracks")
            assertEquals(
                mapOf("item_id" to "0_0_0", "provider_instance_id_or_domain" to "test", "limit" to "$SIMILAR_ITEMS"),
                args.mapValues { it.value.jsonPrimitive.content },
            )
        }
    }

    @Test fun `local files have no similar artists`() = versions.forEach { v ->
        withFakeApi(v, { _, _ -> listOf(FakeMaServer.recorded(v, "music_artists_similar_artists")) }) { api, fake ->
            assertEquals(emptyList<MediaItem>(), api.similarArtists("1", "library"))
            assertEquals("$SIMILAR_ITEMS", fake.args("music/artists/similar_artists")["limit"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `a similar listing leaves out the song it was asked for`() {
        val songs = decodeList("2.11", "music_tracks_similar_tracks", MediaItem.serializer())
        val seed = MediaItem(
            itemId = "13",
            provider = "library",
            name = "The same song, in the library",
            uri = "library://track/13",
            mediaType = "track",
            providerMappings = listOf(ProviderMapping("0_0_2", "test")),
        )
        assertEquals(listOf("test://track/0_0_1", "test://track/0_0_3"), songs.withoutSeed(seed).map { it.uri })
        assertEquals(songs.drop(1), songs.withoutSeed(songs.first()))
    }

    @Test fun `a mix is the server's playlist of its seed`() {
        assertEquals("radio_playlist://playlist/library://album/3", endlessMixUri("library://album/3"))
        assertTrue(isEndlessMix(endlessMixUri("library://album/3")))
        assertFalse(isEndlessMix("library://playlist/7"))
    }

    @Test fun `the queue of a mix says what the mix was made from`() = versions.forEach { v ->
        val queue = decodeOne(v, "player_queues_get__endless_mix", PlayerQueue.serializer())
        assertTrue("$v", queue.isDynamic && queue.shuffleEnabled)
        assertEquals("First Light", queue.endlessMixOf)
        assertNull(decodeOne(v, "player_queues_get__playing", PlayerQueue.serializer()).endlessMixOf)
    }

    @Test fun `a queue's DJ reads from 2_10's name and from 2_11's object`() = versions.forEach { v ->
        withFakeApi(v, { _, _ -> listOf(FakeMaServer.recorded(v, "ai_radio_queue_dj_status")) }) { api, _ ->
            assertEquals(listOf(QueueDj("music_nerd")), api.queueDjs().values.toList())
        }
    }

    @Test fun `a show that armed the DJ is on air`() {
        val status = buildJsonObject {
            putJsonObject("kitchen") {
                put("host_id", "morning_show")
                put("station_id", "s1")
            }
            putJsonObject("office") { put("host_id", "") }
        }
        assertEquals(mapOf("kitchen" to QueueDj("morning_show", onAir = true)), status.queueDjs())
        assertEquals(emptyMap<String, QueueDj>(), JsonPrimitive("unexpected").queueDjs())
    }

    @Test fun `off is sent as null, and the answer holds the queue no more`() = versions.forEach { v ->
        withFakeApi(v, { _, args ->
            val off = args?.get("host_id") is JsonNull
            listOf(FakeMaServer.recorded(v, if (off) "ai_radio_queue_dj_set__off" else "ai_radio_queue_dj_set"))
        }) { api, fake ->
            assertEquals(listOf(QueueDj("music_nerd")), api.setQueueDj("kitchen", "music_nerd").values.toList())
            assertEquals(emptyMap<String, QueueDj>(), api.setQueueDj("kitchen", null))
            val sent = fake.args("ai_radio/queue_dj/set")
            assertEquals(JsonObject(mapOf("queue_id" to JsonPrimitive("kitchen"), "host_id" to JsonNull)), sent)
        }
    }

    @Test fun `hosts have a name to show`() = versions.forEach { v ->
        withFakeApi(v, { _, _ -> listOf(FakeMaServer.recorded(v, "ai_radio_hosts_list")) }) { api, _ ->
            assertEquals(
                listOf("Minimal DJ", "Morning show", "Music nerd", "Party host"),
                api.djHosts().map { it.name },
            )
        }
    }

    @Test fun `without the plugin its commands are unknown to the server`() = versions.forEach { v ->
        withFakeApi(v, { _, _ -> listOf(FakeMaServer.recorded(v, "ai_radio_queue_dj_status__not_loaded")) }) { api, _ ->
            val refused = assertThrows(MaCommandException::class.java) { runBlocking { api.queueDjs() } }
            assertEquals(MaCommandException.ERROR_INVALID_COMMAND, refused.code)
        }
    }

    @Test fun `the providers name the plugins that are loaded`() = versions.forEach { v ->
        val providers = decodeList(v, "providers", Provider.serializer())
        assertTrue("$v", providers.any { it.domain == "radio_playlist" && it.isLoadedPlugin })
        assertTrue("$v", providers.none { it.domain == DJ_PLUGIN })
        assertTrue("$v", providers.first { it.domain == "lastfm_recommendations" }.let { !it.isLoadedPlugin })
    }
}
