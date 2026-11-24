package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.membersOf
import io.github.hazymorning.amsel.playback.R
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerGroupControllerTest {
    private val players = MutableStateFlow<Map<String, Player>>(emptyMap())
    private val listed = MutableStateFlow<List<SpeakerUi>>(emptyList())
    private val messages = MessageController(MutableStateFlow(true))
    private val sent = mutableListOf<String>()
    private var refusal: Exception? = null

    private fun TestScope.groups() = SpeakerGroupController(
        players,
        listed,
        MutableStateFlow("phone"),
        nameOf = { it },
        messages,
        backgroundScope,
    ) { leader, add, remove ->
        sent += "$leader +$add -$remove"
        refusal?.let { throw it }
    }

    private fun speaker(
        id: String,
        syncedTo: String? = null,
        lists: List<String> = emptyList(),
        with: List<String> = emptyList(),
        activeGroup: String? = null,
        available: Boolean = true,
        enabled: Boolean = true,
        type: String = "player",
    ) = Player(
        id,
        name = id.replaceFirstChar(Char::uppercase),
        type = type,
        available = available,
        enabled = enabled,
        groupMembers = lists,
        syncedTo = syncedTo,
        activeGroup = activeGroup,
        supportedFeatures = listOf("set_members"),
        canGroupWith = with,
    )

    // The server's players now, and the speaker list built from them as PlaybackState.speakers does.
    private fun server(vararg all: Player, phoneLeads: Boolean = true, plays: Set<String> = emptySet()) {
        val phone = speaker("phone", with = if (phoneLeads) all.map { it.playerId } else emptyList())
        val map = (all.toList() + phone).associateBy { it.playerId }
        players.value = map
        listed.value = listOf(SpeakerUi("phone", "", isPhone = true)) + map.values
            .filter { it.playerId != "phone" && it.isShownSpeaker("phone") }
            .sortedBy { it.shownName.lowercase() }
            .map {
                SpeakerUi(
                    it.playerId,
                    it.shownName,
                    others = map.membersOf(it.playerId).size,
                    isPlaying = it.playerId in plays,
                )
            }
    }

    private val everyone = listOf("kitchen", "living", "bath", "office")

    private fun kitchenLeadsTwo() = server(
        speaker("kitchen", lists = listOf("kitchen", "bath", "living"), with = everyone),
        speaker("living", syncedTo = "kitchen"),
        speaker("bath", syncedTo = "kitchen"),
        speaker("office", with = everyone),
    )

    private fun everyoneFree() = server(*everyone.map { speaker(it, with = everyone) }.toTypedArray())

    private fun PlayOnGroups.rows() = speakers.map { row ->
        if (row.isPhone) "phone" else row.name + if (row.others > 0) " +${row.others}" else ""
    }

    @Test fun ungroupSendsEachLeaderItsMembersToLetGoAndNeverTheLeader() = runTest {
        server(
            speaker("kitchen", lists = listOf("kitchen", "bath", "living")),
            speaker("living", syncedTo = "kitchen"),
            speaker("bath", syncedTo = "kitchen"),
            speaker("office"),
            speaker("garden", syncedTo = "office"),
        )
        groups().ungroupAll()
        runCurrent()
        assertEquals(listOf("kitchen +[] -[bath, living]", "office +[] -[garden]"), sent)
    }

    @Test fun theSpeakersStandApartAtOnceWhileTheServerStillSaysGrouped() = runTest {
        kitchenLeadsTwo()
        val groups = groups()
        assertEquals(listOf("phone", "Kitchen +2", "Office"), groups.playOnNow("phone").rows())
        assertTrue(groups.playOnNow("phone").grouped)

        groups.ungroupAll()
        val shown = groups.playOnNow("phone")
        assertEquals(listOf("phone", "Bath", "Kitchen", "Living", "Office"), shown.rows())
        assertFalse(shown.grouped)
    }

    @Test fun aSpeakerTheServerNamesLaterIsLetGoThen() = runTest {
        // Two seconds after the group was made, only the Living room reports synced_to.
        server(speaker("kitchen"), speaker("living", syncedTo = "kitchen"), speaker("bath"))
        val groups = groups()
        groups.ungroupAll()
        runCurrent()
        assertEquals(listOf("kitchen +[] -[living]"), sent)

        // The Living room has left; now the Bath reports synced_to, and the leader lists both.
        val both = speaker("kitchen", lists = listOf("kitchen", "bath", "living"))
        server(both, speaker("living"), speaker("bath", syncedTo = "kitchen"))
        runCurrent()
        assertEquals(listOf("kitchen +[] -[living]", "kitchen +[] -[bath]"), sent)
        assertEquals(listOf("phone", "Bath", "Kitchen", "Living"), groups.playOnNow("phone").rows())

        // The leader's list is two seconds behind: nobody is asked to leave twice.
        server(both, speaker("living"), speaker("bath"))
        advanceTimeBy(2_000)
        server(speaker("kitchen"), speaker("living"), speaker("bath"))
        advanceTimeBy(6_000)
        assertEquals(2, sent.size)
        assertFalse(groups.playOnNow("phone").grouped)
    }

    @Test fun aSpeakerAskedToJoinAMomentAgoIsLetGoOnceItsLeaderListsIt() = runTest {
        everyoneFree()
        val groups = groups()
        groups.set("kitchen", add = listOf("living", "bath"), remove = emptyList())
        runCurrent()
        // The leader lists both before the Bath reports synced_to (docs/API-NOTES.md, "Player commands").
        val leader = speaker("kitchen", lists = listOf("kitchen", "bath", "living"), with = everyone)
        server(leader, speaker("living", syncedTo = "kitchen"), speaker("bath"), speaker("office"))
        groups.ungroupAll()
        runCurrent()
        assertEquals(listOf("kitchen +[living, bath] -[]", "kitchen +[] -[bath, living]"), sent)
    }

    @Test fun aSpeakerThatJustLeftIsNotAskedToLeaveAgain() = runTest {
        kitchenLeadsTwo()
        val groups = groups()
        groups.set("kitchen", add = emptyList(), remove = listOf("living"))
        runCurrent()
        // The Living room is out, but its leader still lists it a while, and asking again is an error.
        val stale = speaker("kitchen", lists = listOf("kitchen", "bath", "living"), with = everyone)
        server(stale, speaker("living"), speaker("bath", syncedTo = "kitchen"), speaker("office"))
        groups.ungroupAll()
        runCurrent()
        assertEquals(listOf("kitchen +[] -[living]", "kitchen +[] -[bath]"), sent)
    }

    @Test fun aRefusalBringsTheGroupBackAndSaysSoWithRetry() = runTest {
        kitchenLeadsTwo()
        val groups = groups()
        refusal = MaCommandException("players/cmd/set_members", MaCommandException.ERROR_PLAYER_UNAVAILABLE, "gone")
        groups.ungroupAll()
        runCurrent()
        assertEquals(listOf("phone", "Kitchen +2", "Office"), groups.playOnNow("phone").rows())
        val message = messages.pending.value.single()
        assertEquals(R.string.error_speaker_unavailable, message.text)
        assertEquals("kitchen", message.arg)

        refusal = null
        checkNotNull(message.retry).invoke()
        runCurrent()
        assertEquals(List(2) { "kitchen +[] -[bath, living]" }, sent)
        assertFalse(groups.playOnNow("phone").grouped)
    }

    @Test fun theGroupStepChangesTheGroupThroughItsLeaderAndShowsAtOnce() = runTest {
        kitchenLeadsTwo()
        val groups = groups()
        groups.set("kitchen", add = listOf("office"), remove = listOf("living"))
        assertEquals(listOf("phone", "Kitchen +2", "Living"), groups.playOnNow("phone").rows())
        runCurrent()
        assertEquals(listOf("kitchen +[office] -[living]"), sent)

        // Once the server names the group as asked, its word shows again.
        server(
            speaker("kitchen", lists = listOf("kitchen", "bath", "office")),
            speaker("living"),
            speaker("bath", syncedTo = "kitchen"),
            speaker("office", syncedTo = "kitchen"),
        )
        advanceTimeBy(6_000)
        assertEquals(listOf("phone", "Kitchen +2", "Living"), groups.playOnNow("phone").rows())
        assertEquals(1, sent.size)
    }

    @Test fun aGroupAskedForShowsAtOnceAndGivesWayToTheServersWordOnceItNamesIt() = runTest {
        everyoneFree()
        val groups = groups()
        groups.set("kitchen", add = listOf("living", "bath"), remove = emptyList())
        runCurrent()
        assertEquals(listOf("kitchen +[living, bath] -[]"), sent)
        assertEquals(listOf("phone", "Kitchen +2", "Office"), groups.playOnNow("phone").rows())

        // Named one by one, two seconds apart: the list does not follow each step.
        val leader = speaker("kitchen", with = everyone)
        server(leader, speaker("living", syncedTo = "kitchen"), speaker("bath"), speaker("office"))
        advanceTimeBy(2_000)
        assertEquals(listOf("phone", "Kitchen +2", "Office"), groups.playOnNow("phone").rows())
        kitchenLeadsTwo()
        runCurrent()

        // From here on the server's word counts at once: someone else lets the Bath go.
        val one = speaker("kitchen", lists = listOf("kitchen", "living"))
        server(one, speaker("living", syncedTo = "kitchen"), speaker("bath"))
        assertEquals(listOf("phone", "Bath", "Kitchen +1"), groups.playOnNow("phone").rows())
    }

    @Test fun aSpeakerLetGoBeforeTheServerNamedItLeavesOnceItIsNamed() = runTest {
        everyoneFree()
        val groups = groups()
        groups.set("kitchen", add = listOf("bath"), remove = emptyList())
        runCurrent()
        groups.set("kitchen", add = emptyList(), remove = listOf("bath"))
        runCurrent()
        // The server would pass over a speaker it does not name yet: nothing is sent for it.
        assertEquals(listOf("kitchen +[bath] -[]"), sent)
        assertFalse(groups.playOnNow("phone").grouped)

        server(speaker("kitchen", with = everyone), speaker("bath", syncedTo = "kitchen"))
        runCurrent()
        assertEquals(listOf("kitchen +[bath] -[]", "kitchen +[] -[bath]"), sent)
    }

    @Test fun aLaterWishForTheLeaderEndsTheEarlierOne() = runTest {
        kitchenLeadsTwo()
        val groups = groups()
        groups.ungroupAll()
        runCurrent()
        everyoneFree()
        groups.set("kitchen", add = listOf("office"), remove = emptyList())
        runCurrent()
        // The Office joins: Ungroup, asked for before, leaves it be.
        server(speaker("kitchen", lists = listOf("kitchen", "office")), speaker("office", syncedTo = "kitchen"))
        advanceTimeBy(6_000)
        assertEquals(listOf("kitchen +[] -[bath, living]", "kitchen +[office] -[]"), sent)
    }

    @Test fun groupBelongsToTheGroupThatExistsElseToTheSpeakerInUse() = runTest {
        everyoneFree()
        val groups = groups()
        val none = groups.playOnNow("office")
        assertEquals("office", none.leader?.id)
        assertEquals(listOf("Bath", "Kitchen", "Living"), none.choices.map { it.name })
        assertTrue(none.choices.none { it.member })

        kitchenLeadsTwo()
        // The Office is in use and leads nothing: the step is Kitchen's, its members first.
        val other = groups.playOnNow("office")
        assertEquals("kitchen", other.leader?.id)
        val choices = other.choices.map { it.name to it.member }
        assertEquals(listOf("Bath" to true, "Living" to true, "Office" to false), choices)

        // Two groups: the speaker in use's own comes before the first in the list.
        server(
            speaker("kitchen", lists = listOf("kitchen", "living")),
            speaker("living", syncedTo = "kitchen"),
            speaker("office", lists = listOf("office", "bath")),
            speaker("bath", syncedTo = "office"),
        )
        assertEquals("office", groups.playOnNow("office").leader?.id)
        assertEquals("kitchen", groups.playOnNow("phone").leader?.id)
    }

    // As from a widget's Group on This phone, which cannot lead a group.
    @Test fun whereTheSpeakerInUseCannotLeadTheStepIsForOneThatCan() = runTest {
        val three = listOf("bath", "kitchen", "living")
        server(*three.map { speaker(it, with = three) }.toTypedArray(), phoneLeads = false)
        val groups = groups()
        val first = groups.playOnNow("phone")
        assertEquals("bath", first.leader?.id)
        assertEquals(listOf("Kitchen", "Living"), first.choices.map { it.name })

        server(*three.map { speaker(it, with = three) }.toTypedArray(), phoneLeads = false, plays = setOf("living"))
        assertEquals("living", groups.playOnNow("phone").leader?.id)

        // None of them can lead: no group step, only the list.
        server(*three.map { speaker(it) }.toTypedArray(), phoneLeads = false)
        assertTrue(groups.playOnNow("phone").choices.isEmpty())
    }

    @Test fun whatAGroupPlayerRunsIsNoGroupToLetGoOrChange() = runTest {
        // "Downstairs", made in Music Assistant, plays: Kitchen leads the Living room in step for it.
        server(
            speaker("downstairs", lists = listOf("kitchen", "living"), with = everyone, type = "group"),
            speaker("kitchen", lists = listOf("kitchen", "living"), with = everyone, activeGroup = "downstairs"),
            speaker("living", syncedTo = "kitchen", activeGroup = "downstairs"),
            speaker("bath", with = everyone),
        )
        val groups = groups()
        val shown = groups.playOnNow("downstairs")
        assertEquals(listOf("phone", "Bath", "Downstairs"), shown.rows())
        assertFalse(shown.grouped)
        assertTrue(shown.choices.isEmpty())
        // Nor is one of its speakers offered to another group.
        assertTrue(groups.playOnNow("bath").choices.isEmpty())

        groups.ungroupAll()
        runCurrent()
        assertTrue(sent.isEmpty())
    }

    @Test fun aSpeakerOutOfReachMakesNoGroupAndIsNeverSent() = runTest {
        // The Hall was disabled in Music Assistant while it played along: it still reports synced_to.
        server(
            speaker("kitchen", lists = listOf("kitchen", "living", "garden")),
            speaker("living", syncedTo = "kitchen"),
            speaker("garden", available = false),
            speaker("hall", syncedTo = "kitchen", enabled = false),
        )
        val groups = groups()
        assertEquals(listOf("phone", "Garden", "Kitchen +1"), groups.playOnNow("phone").rows())
        groups.ungroupAll()
        runCurrent()
        assertEquals(listOf("kitchen +[] -[living]"), sent)

        sent.clear()
        server(speaker("kitchen"), speaker("hall", syncedTo = "kitchen", enabled = false))
        assertFalse(groups.playOnNow("phone").grouped)
        groups.ungroupAll()
        runCurrent()
        assertTrue(sent.isEmpty())
        assertNull(messages.pending.value.firstOrNull())
    }
}
