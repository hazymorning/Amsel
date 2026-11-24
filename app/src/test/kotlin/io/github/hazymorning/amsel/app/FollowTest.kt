package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.api.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FollowTest {
    private fun speaker(id: String, playing: Boolean, syncedTo: String? = null) =
        Player(id, name = id, playbackState = if (playing) "playing" else "idle", syncedTo = syncedTo)

    private fun of(vararg players: Player) = players.associateBy { it.playerId }

    @Test fun `the speaker that plays is followed, a group's leader before a lone one`() {
        val players = of(
            speaker("kitchen", playing = true),
            speaker("living", playing = true),
            speaker("bedroom", playing = true, syncedTo = "living"),
            speaker("phone", playing = false),
        )
        assertEquals("living", speakerToFollow(players, "phone", phoneAudible = false)?.playerId)
    }

    @Test fun `this phone wins whenever it plays itself`() {
        val players = of(speaker("kitchen", playing = true), speaker("phone", playing = true))
        assertNull(speakerToFollow(players, "phone", phoneAudible = false))
        // Its own audio counts before the server reports it.
        val quiet = of(speaker("kitchen", playing = true), speaker("phone", playing = false))
        assertNull(speakerToFollow(quiet, "phone", phoneAudible = true))
    }

    @Test fun `only the speakers left in are followed, one in a group as that group`() {
        val players = of(
            speaker("kitchen", playing = true),
            speaker("living", playing = true),
            speaker("bedroom", playing = true, syncedTo = "living"),
            speaker("phone", playing = false),
        )
        // Only Kitchen is left in, so the larger group no longer wins.
        assertEquals(
            "kitchen",
            speakerToFollow(players, "phone", false, skipped = setOf("living", "bedroom"))?.playerId,
        )
        // Only the bedroom: it plays in the living room's group, so that group is followed.
        assertEquals("living", speakerToFollow(players, "phone", false, skipped = setOf("living", "kitchen"))?.playerId)
        assertNull(speakerToFollow(players, "phone", false, skipped = setOf("living", "kitchen", "bedroom")))
    }

    @Test fun `with nothing playing it is this phone`() {
        assertNull(speakerToFollow(of(speaker("kitchen", playing = false)), "phone", phoneAudible = false))
    }
}
