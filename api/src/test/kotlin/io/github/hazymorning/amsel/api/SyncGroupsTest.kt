package io.github.hazymorning.amsel.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which speakers form a group Play on can change, from what the server says of them. */
class SyncGroupsTest {
    private fun speaker(
        id: String,
        syncedTo: String? = null,
        lists: List<String> = emptyList(),
        leads: Boolean = true,
        activeGroup: String? = null,
        available: Boolean = true,
        enabled: Boolean = true,
        type: String = "player",
    ) = Player(
        id,
        name = id,
        type = type,
        available = available,
        enabled = enabled,
        groupMembers = lists,
        syncedTo = syncedTo,
        activeGroup = activeGroup,
        supportedFeatures = if (leads) listOf("set_members") else emptyList(),
    )

    private fun groups(vararg players: Player) = players.associateBy { it.playerId }.syncGroups()

    @Test fun `speakers on their own are no group`() {
        assertTrue(groups(speaker("kitchen"), speaker("living"), speaker("bath")).isEmpty())
    }

    @Test fun `a group is its leader and the speakers that say they play along with it`() {
        val settled = groups(
            speaker("kitchen", lists = listOf("kitchen", "living", "bath")),
            speaker("living", syncedTo = "kitchen"),
            speaker("bath", syncedTo = "kitchen"),
            speaker("office"),
        )
        assertEquals(mapOf("kitchen" to setOf("living", "bath")), settled)
    }

    @Test fun `a speaker counts once it says so itself, whatever the leader lists`() {
        // 2.10.9, four seconds after two joined: both say so, the leader lists nobody yet.
        val said = groups(
            speaker("kitchen"),
            speaker("living", syncedTo = "kitchen"),
            speaker("bath", syncedTo = "kitchen"),
        )
        assertEquals(mapOf("kitchen" to setOf("living", "bath")), said)
        // 2.11.0b7, two seconds in: the leader lists both, only one has said so.
        val listed = groups(
            speaker("kitchen", lists = listOf("kitchen", "bath", "living")),
            speaker("living", syncedTo = "kitchen"),
            speaker("bath"),
        )
        assertEquals(mapOf("kitchen" to setOf("living")), listed)
    }

    @Test fun `a speaker that left is out at once, though its leader lists it for two more seconds`() {
        // Asking it to leave again in those seconds is an error on 2.10.9 and 2.11.0b7.
        val left = groups(
            speaker("kitchen", lists = listOf("kitchen", "bath", "living")),
            speaker("living"),
            speaker("bath", syncedTo = "kitchen"),
        )
        assertEquals(mapOf("kitchen" to setOf("bath")), left)
        assertTrue(groups(speaker("kitchen", lists = listOf("kitchen", "living")), speaker("living")).isEmpty())
    }

    @Test fun `a speaker out of reach or switched off plays along with nobody`() {
        assertTrue(groups(speaker("kitchen"), speaker("hall", syncedTo = "kitchen", available = false)).isEmpty())
        // One switched off in Music Assistant is not heard of again: its last word stays as it was.
        assertTrue(groups(speaker("kitchen"), speaker("hall", syncedTo = "kitchen", enabled = false)).isEmpty())
    }

    @Test fun `a leader out of reach, or one that cannot take speakers along, leads no group`() {
        assertTrue(groups(speaker("kitchen", available = false), speaker("living", syncedTo = "kitchen")).isEmpty())
        assertTrue(groups(speaker("kitchen", enabled = false), speaker("living", syncedTo = "kitchen")).isEmpty())
        assertTrue(groups(speaker("kitchen", leads = false), speaker("living", syncedTo = "kitchen")).isEmpty())
        assertTrue(groups(speaker("living", syncedTo = "kitchen")).isEmpty())
    }

    @Test fun `what a group player runs is not a group of its own, playing or at rest`() {
        // At rest the group player lists its speakers, and they are free.
        val resting = groups(
            speaker("downstairs", lists = listOf("kitchen", "living"), type = "group"),
            speaker("kitchen"),
            speaker("living"),
        )
        assertTrue(resting.isEmpty())
        // While it plays, one of them leads the other in step, both run by the group player.
        val playing = groups(
            speaker("downstairs", lists = listOf("kitchen", "living"), type = "group"),
            speaker("kitchen", lists = listOf("kitchen", "living"), activeGroup = "downstairs"),
            speaker("living", syncedTo = "kitchen", activeGroup = "downstairs"),
        )
        assertTrue(playing.isEmpty())
    }

    @Test fun `several groups stand side by side`() {
        val two = groups(
            speaker("kitchen", lists = listOf("kitchen", "living")),
            speaker("living", syncedTo = "kitchen"),
            speaker("bath"),
            speaker("office", syncedTo = "bath"),
        )
        assertEquals(mapOf("kitchen" to setOf("living"), "bath" to setOf("office")), two)
    }

    @Test fun `a speaker that itself plays along leads nobody`() {
        // 2.10.9 while a group passes to another leader: for a moment a member names a member.
        val passing = groups(
            speaker("hall", lists = listOf("hall", "kitchen", "living")),
            speaker("living", syncedTo = "hall"),
            speaker("kitchen", syncedTo = "living"),
        )
        assertEquals(mapOf("hall" to setOf("living")), passing)
    }
}
