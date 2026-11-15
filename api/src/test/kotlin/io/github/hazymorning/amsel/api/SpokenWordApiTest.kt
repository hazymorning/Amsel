package io.github.hazymorning.amsel.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenWordApiTest {
    @Test fun `2_11 asks the server for one page of episodes, newest first`() = withFakeApi("2.11", { _, _ ->
        listOf(FakeMaServer.recorded("2.11", "music_podcasts_podcast_episodes__page"))
    }) { api, fake ->
        val page = api.podcastEpisodes("1", "library", offset = 50, limit = 50)
        assertEquals(listOf(3L, 2L), page.items.map { it.position })
        assertTrue(page.complete)
        val args = fake.args("music/podcasts/podcast_episodes")
        assertEquals("50", args["offset"]!!.jsonPrimitive.content)
        assertEquals("50", args["limit"]!!.jsonPrimitive.content)
        assertEquals("position", args["sort_field"]!!.jsonPrimitive.content)
        assertEquals("desc", args["sort_direction"]!!.jsonPrimitive.content)
    }

    @Test fun `2_10 joins the streamed episodes and sends them all at once`() = withFakeApi("2.10", { _, _ ->
        val items = fixture("2.10", "music_podcasts_podcast_episodes")["response"]!!.jsonObject["result"] as JsonArray
        listOf(
            FakeMaServer.result(JsonArray(items.take(2)), partial = true),
            FakeMaServer.result(JsonArray(items.drop(2))),
        )
    }) { api, fake ->
        val page = api.podcastEpisodes("1", "library", limit = 2)
        assertEquals(listOf(3L, 2L, 1L), page.items.map { it.position })
        assertTrue(page.complete)
        assertFalse(fake.args("music/podcasts/podcast_episodes").containsKey("limit"))
    }

    @Test fun `marking played sends the item, never its uri`() = withFakeApi("2.11", { _, _ ->
        listOf(FakeMaServer.result(null))
    }) { api, fake ->
        val book = ItemUri("library", "audiobook", "2")
        api.setPlayed(book, "The Lantern Road", played = true)
        val item = fake.args("music/mark_played")["media_item"]!!.jsonObject
        assertEquals(
            mapOf("item_id" to "2", "provider" to "library", "name" to "The Lantern Road", "media_type" to "audiobook"),
            item.mapValues { (it.value as JsonPrimitive).content },
        )
        api.setPlayed(book, "The Lantern Road", played = false)
        assertTrue(fake.args("music/mark_unplayed")["media_item"] is JsonObject)
    }

    @Test fun `in progress and the newest episodes`() = withFakeApi("2.11", { command, _ ->
        when (command) {
            "music/in_progress_items" -> listOf(FakeMaServer.recorded("2.11", "music_in_progress_items__episode"))
            else -> listOf(FakeMaServer.recorded("2.11", "music_podcasts_podcast_episodes"))
        }
    }) { api, fake ->
        assertEquals(MediaType.PodcastEpisode.api, api.inProgress(13).single().mediaType)
        assertEquals("13", fake.args("music/in_progress_items")["limit"]!!.jsonPrimitive.content)
        assertEquals(3, api.latestEpisodes().size)
        val row = fake.args("music/recommendations/items")
        assertEquals("latest_episodes", row["item_id"]!!.jsonPrimitive.content)
        assertEquals("recommendations", row["provider"]!!.jsonPrimitive.content)
    }

    @Test fun `search asks for books and podcasts too`() = withFakeApi("2.11", { _, _ ->
        listOf(FakeMaServer.recorded("2.11", "music_search"))
    }) { api, fake ->
        api.search("aurora")
        val types = (fake.args("music/search")["media_types"] as JsonArray).map { it.jsonPrimitive.content }
        assertTrue(types.containsAll(listOf("audiobook", "podcast")))
    }

    @Test fun `a chapter starts as the start item`() = withFakeApi("2.11", { _, _ ->
        listOf(FakeMaServer.result(null))
    }) { api, fake ->
        api.playMedia("kitchen", listOf("library://audiobook/2"), startItem = "3")
        assertEquals("3", fake.args("player_queues/play_media")["start_item"]!!.jsonPrimitive.content)
    }
}
