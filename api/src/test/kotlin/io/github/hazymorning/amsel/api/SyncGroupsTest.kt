package io.github.hazymorning.amsel.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        // Both members name the leader, which lists nobody yet. See docs/API-NOTES.md, "Player fields used".
        val said = groups(
            speaker("kitchen"),
            speaker("living", syncedTo = "kitchen"),
            speaker("bath", syncedTo = "kitchen"),
        )
        assertEquals(mapOf("kitchen" to setOf("living", "bath")), said)
        // The leader lists both; only one member names it yet.
        val listed = groups(
            speaker("kitchen", lists = listOf("kitchen", "bath", "living")),
            speaker("living", syncedTo = "kitchen"),
            speaker("bath"),
        )
        assertEquals(mapOf("kitchen" to setOf("living")), listed)
    }

    @Test fun `a speaker that left is out at once, though its leader lists it for two more seconds`() {
        // Asking it to leave again then is an error. See docs/API-NOTES.md, "Player fields used".
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
        // A player disabled in Music Assistant sends no more updates, so its last synced_to stays.
        assertTrue(groups(speaker("kitchen"), speaker("hall", syncedTo = "kitchen", enabled = false)).isEmpty())
    }

    @Test fun `a leader out of reach, or one that cannot take speakers along, leads no group`() {
        assertTrue(groups(speaker("kitchen", available = false), speaker("living", syncedTo = "kitchen")).isEmpty())
        assertTrue(groups(speaker("kitchen", enabled = false), speaker("living", syncedTo = "kitchen")).isEmpty())
        assertTrue(groups(speaker("kitchen", leads = false), speaker("living", syncedTo = "kitchen")).isEmpty())
        assertTrue(groups(speaker("living", syncedTo = "kitchen")).isEmpty())
    }

    @Test fun `what a group player runs is not a group of its own, playing or at rest`() {
        // Idle, the group player lists its speakers, which are not synced.
        val resting = groups(
            speaker("downstairs", lists = listOf("kitchen", "living"), type = "group"),
            speaker("kitchen"),
            speaker("living"),
        )
        assertTrue(resting.isEmpty())
        // Playing, one member is synced to the other, both with active_group set.
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
        // While the lead passes to another speaker, a member can name a member for a moment.
        // See docs/API-NOTES.md, "Player fields used".
        val passing = groups(
            speaker("hall", lists = listOf("hall", "kitchen", "living")),
            speaker("living", syncedTo = "hall"),
            speaker("kitchen", syncedTo = "living"),
        )
        assertEquals(mapOf("hall" to setOf("living")), passing)
    }
}
