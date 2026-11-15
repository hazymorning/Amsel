package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.api.ConnectionState
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaAuth
import io.github.hazymorning.amsel.api.MaClient
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.api.ServerAddress
import io.github.hazymorning.amsel.api.SignInResult
import io.github.hazymorning.amsel.api.libraryItems
import io.github.hazymorning.amsel.api.syncGroups
import io.github.hazymorning.amsel.data.Speakers
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Play on's groups against a real Music Assistant and its fake speakers (scripts/test-server), the
 * server's events keeping the speakers current as in the app. Skipped unless MA_URL is set.
 */
class SpeakerGroupsLiveTest {
    private class Live(val api: MaApi, val speakers: Speakers, val groups: SpeakerGroups, val sent: List<String>) {
        /** The speakers in reach, Kitchen first. */
        val free = speakers.players.value.values.filter { it.canLeadGroup }
            .sortedBy { it.shownName != "Kitchen" }
            .map { it.playerId }

        fun grouped() = speakers.players.value.values.filter { it.syncedTo != null || it.groupMembers.isNotEmpty() }

        /** The speakers asked to leave so far, one entry for each asking. */
        fun letGo() = sent.flatMap { it.substringAfter("-[").removeSuffix("]").split(", ") }.filter { it.isNotEmpty() }
    }

    @Test fun `a group made and let go at once is gone for good`() = live {
        val kitchen = free.first()
        val others = free.drop(1)
        groups.set(kitchen, add = others, remove = emptyList())
        assertEquals(others.toSet(), groups.playOnNow(kitchen).choices.filter { it.member }.map { it.id }.toSet())

        // Ungroup, a moment after Done: the server has named one of the speakers that joined, if any.
        delay(TAP_MS)
        groups.ungroupAll()
        assertFalse(groups.playOnNow(kitchen).grouped)

        // Every speaker that joined is asked to leave once, in as many commands as the server takes to name them.
        until { letGo().sorted() == others.sorted() }
        until { grouped().isEmpty() }
        delay(QUIET_MS)
        assertEquals(emptyList<String>(), grouped().map { it.shownName })
        assertFalse(groups.playOnNow(kitchen).grouped)
        assertEquals(others.sorted(), letGo().sorted())
    }

    @Test fun `a group is let go while another speaker is in use, and its leader plays on`() = live {
        val (kitchen, living) = free
        val inUse = free.getOrElse(2) { living }
        try {
            api.playMedia(kitchen, listOf(api.libraryItems(MediaType.Album).first().uri))
            api.setRepeat(kitchen, RepeatMode.All)
            groups.set(kitchen, add = listOf(living), remove = emptyList())
            until { speakers.players.value.syncGroups() == mapOf(kitchen to setOf(living)) }
            // The group step is the group's, whichever speaker is in use.
            assertEquals(kitchen, groups.playOnNow(inUse).leader?.id)

            groups.ungroupAll()
            until { grouped().isEmpty() }
            delay(QUIET_MS)
            assertEquals(emptyList<String>(), grouped().map { it.shownName })
            assertEquals(listOf("$kitchen +[$living] -[]", "$kitchen +[] -[$living]"), sent)
            assertTrue(api.queues().first { it.queueId == kitchen }.isPlaying)
        } finally {
            api.setRepeat(kitchen, RepeatMode.Off)
            api.clearQueue(kitchen)
        }
    }

    @Test fun `a speaker let go a moment ago is not asked to leave again with the rest`() = live {
        assumeTrue("needs three speakers", free.size >= 3)
        val (kitchen, living, bath) = free
        groups.set(kitchen, add = listOf(living, bath), remove = emptyList())
        until { speakers.players.value.syncGroups() == mapOf(kitchen to setOf(living, bath)) }

        // Its leader goes on listing it for two seconds, and asking it to leave again then is an error.
        groups.set(kitchen, add = emptyList(), remove = listOf(living))
        delay(TAP_MS)
        groups.ungroupAll()
        until { grouped().isEmpty() }
        delay(QUIET_MS)
        assertEquals(emptyList<String>(), grouped().map { it.shownName })
        assertEquals(listOf(living, bath), letGo())
    }

    private suspend fun until(done: () -> Boolean) = withTimeout(SETTLE_MS) {
        while (!done()) delay(POLL_MS)
    }

    private fun live(block: suspend Live.() -> Unit) = runBlocking {
        val url: String? = System.getenv("MA_URL")
        assumeTrue("MA_URL not set", url != null)
        val address = checkNotNull(ServerAddress.parse(checkNotNull(url)))
        val http = OkHttpClient()
        val signIn = MaAuth(http).signIn(address, "amsel", "amsel-test-pw", "Amsel (live test)")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = MaClient(http, scope)
        try {
            client.start(address, (signIn as SignInResult.Success).token)
            withTimeout(CONNECT_MS) { client.state.filterIsInstance<ConnectionState.Connected>().first() }
            val api = MaApi(client)
            val speakers = Speakers(api, elapsedRealtime = { System.nanoTime() / NANOS_PER_MS })
            scope.launch { client.events.collect(speakers::onEvent) }
            speakers.refresh()
            val sent = CopyOnWriteArrayList<String>()
            val messages = Messages(MutableStateFlow(true))
            val groups = SpeakerGroups(
                speakers.players,
                listed = MutableStateFlow(emptyList()),
                phoneId = MutableStateFlow(null),
                nameOf = { it },
                messages,
                scope,
            ) { leader, add, remove ->
                sent += "$leader +$add -$remove"
                api.setMembers(leader, add, remove)
            }
            val live = Live(api, speakers, groups, sent)
            assumeTrue("needs two speakers", live.free.size >= 2)
            try {
                live.block()
                assertEquals(emptyList<Message>(), messages.pending.value)
            } finally {
                speakers.players.value.syncGroups().forEach { (leader, members) ->
                    api.setMembers(leader, emptyList(), members.toList())
                }
            }
        } finally {
            client.stop()
            scope.cancel()
        }
    }

    private companion object {
        const val CONNECT_MS = 15_000L

        /** How soon after Done a quick finger is on Ungroup. */
        const val TAP_MS = 700L

        /** The server names a group's speakers two seconds apart. */
        const val SETTLE_MS = 30_000L
        const val POLL_MS = 100L

        /** Past the five seconds Ungroup follows the server's word for: what is free then stays free. */
        const val QUIET_MS = 6_000L

        const val NANOS_PER_MS = 1_000_000L
    }
}
