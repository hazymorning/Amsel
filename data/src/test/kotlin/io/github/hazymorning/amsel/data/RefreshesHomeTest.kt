package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.MaEvent
import io.github.hazymorning.amsel.api.fixture
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshesHomeTest {
    private fun recorded(name: String): MaEvent {
        val r = fixture("2.11", name)["response"]!!.jsonObject
        return MaEvent(r["event"]!!.jsonPrimitive.content, r["object_id"]?.jsonPrimitive?.content, r["data"])
    }

    private fun MaEvent.with(key: String, value: JsonPrimitive) =
        copy(data = JsonObject(data!!.jsonObject + (key to value)))

    @Test fun `a finished play and a library change refresh Home`() {
        assertTrue(refreshesHome(recorded("event_media_item_played"), userId = "me"))
        assertTrue(refreshesHome(MaEvent("media_item_added", "library://album/1", null), userId = "me"))
        assertFalse(refreshesHome(MaEvent("queue_updated", "kitchen", null), userId = "me"))
    }

    @Test fun `a song added or liked does not, as Home lists songs only as played`() {
        assertFalse(refreshesHome(MaEvent("media_item_added", "library://track/12", null), userId = "me"))
        assertFalse(refreshesHome(MaEvent("favorite_updated", "library://track/12", null), userId = "me"))
        assertTrue(refreshesHome(recorded("event_favorite_updated"), userId = "me"))
    }

    @Test fun `the report sent every 30 seconds while playing does not`() {
        val playing = recorded("event_media_item_played__audiobook").with("is_playing", JsonPrimitive(true))
        assertFalse(refreshesHome(playing, userId = null))
    }

    @Test fun `only the signed-in person's progress, or everyone's, refreshes Home`() {
        val paused = recorded("event_playlog_updated__audiobook")
        val owner = paused.data!!.jsonObject["userid"]!!.jsonPrimitive.content
        assertTrue(refreshesHome(paused, userId = owner))
        assertFalse(refreshesHome(paused, userId = "someone else"))
        assertTrue(refreshesHome(paused.with("userid", JsonNull), userId = "x"))
    }
}
