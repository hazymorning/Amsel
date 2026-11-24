package io.github.hazymorning.amsel.app

import androidx.compose.runtime.Immutable
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.syncGroups
import io.github.hazymorning.amsel.data.DataGraph
import io.github.hazymorning.amsel.playback.PhoneSpeaker
import io.github.hazymorning.amsel.playback.failureText
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class SpeakerGroupController internal constructor(
    private val players: StateFlow<Map<String, Player>>,
    private val listed: StateFlow<List<SpeakerUi>>,
    private val phoneId: StateFlow<String?>,
    private val nameOf: (String) -> String,
    private val messages: MessageController,
    private val scope: CoroutineScope,
    /** Times the speakers were read since signing in; 0 while connecting. */
    private val loads: StateFlow<Int> = MutableStateFlow(1),
    private val setMembers: suspend (leaderId: String, add: List<String>, remove: List<String>) -> Unit,
) {
    constructor(
        data: DataGraph,
        playing: PlaybackState,
        phone: PhoneSpeaker,
        messages: MessageController,
        scope: CoroutineScope,
    ) : this(
        data.speakers.players,
        playing.speakers,
        phone.playerId,
        playing::nameOf,
        messages,
        scope,
        data.speakers.loads,
        { leaderId, add, remove -> data.session.api.setMembers(leaderId, add, remove) },
    )

    // Members asked for and not yet settled, by leader; an empty set lets the group go.
    private val asked = MutableStateFlow<Map<String, PendingGroup>>(emptyMap())

    // Commands go out in the order they were asked for: each builds on the one before.
    private val sending = Mutex()

    fun playOn(currentId: String): Flow<PlayOnGroups> =
        combine(players, asked, phoneId, listed, loads) { players, asked, phoneId, listed, loads ->
            playOnGroups(players, asked.members(), phoneId, listed, currentId, known = loads > 0)
        }

    /** For the sheet's first frame. */
    fun playOnNow(currentId: String): PlayOnGroups =
        playOnGroups(players.value, asked.value.members(), phoneId.value, listed.value, currentId, loads.value > 0)

    fun set(leaderId: String, add: List<String>, remove: List<String>) {
        if (add.isEmpty() && remove.isEmpty()) return
        val wish = groups()[leaderId].orEmpty() + add - remove.toSet()
        ask(leaderId, wish, add, remove.toSet()) { set(leaderId, add, remove) }
    }

    fun ungroupAll() = groups().keys.forEach(::letGo)

    private fun letGo(leaderId: String) {
        ask(leaderId, emptySet(), emptyList(), leaving = null) { letGo(leaderId) }
    }

    private fun groups() = players.value.syncGroups().with(asked.value.members())

    private fun ask(
        leaderId: String,
        members: Set<String>,
        add: List<String>,
        leaving: Set<String>?,
        retry: () -> Unit,
    ) {
        val wish = PendingGroup(members)
        val joining = asked.value[leaderId]?.members.orEmpty()
        asked.update { it + (leaderId to wish) }
        scope.launch {
            val error = try {
                settle(leaderId, wish, add, leaving, joining)
                null
            } catch (e: MaException) {
                e
            }
            asked.update { if (it[leaderId] === wish) it - leaderId else it }
            if (error != null) messages.show(failureText(error), nameOf(leaderId), retry)
        }
    }

    // A speaker that just joined is named seconds later and can be let go only once named, so this
    // follows the server until all are named (adds only) or its word stood still for GROUP_CONFIRM_MS.
    // [leaving] null is every member. See docs/API-NOTES.md, "Player commands".
    private suspend fun settle(
        leaderId: String,
        wish: PendingGroup,
        add: List<String>,
        leaving: Set<String>?,
        joining: Set<String>,
    ) {
        // [joining], asked by the wish this one replaces, count from the leader's own list too, which
        // names them first; any other member counts only by its own synced_to ([syncGroups]).
        fun members(players: Map<String, Player>): Set<String> {
            val listed = players[leaderId]?.takeIf { it.canLeadGroup }?.groupMembers.orEmpty()
            return players.syncGroups()[leaderId].orEmpty() +
                listed.filter { it in joining && players[it]?.canPlayAlong == true }
        }
        val gone = mutableSetOf<String>()
        var join = add
        while (true) {
            val named = sending.withLock {
                val named = members(players.value)
                val leave = (leaving?.let(named::intersect) ?: named) - gone
                if (join.isNotEmpty() || leave.isNotEmpty()) setMembers(leaderId, join, leave.sorted())
                gone += leave
                join = emptyList()
                named
            }
            if (leaving?.isEmpty() == true && named == wish.members) return
            val word = withTimeoutOrNull(GROUP_CONFIRM_MS) {
                combine(players, asked) { players, asked ->
                    when {
                        asked[leaderId] !== wish -> GroupChange.Superseded
                        members(players) != named -> GroupChange.Changed
                        else -> null
                    }
                }.filterNotNull().first()
            }
            if (word != GroupChange.Changed) return
        }
    }

    // Compared by identity: a later wish for the same members is still another.
    private class PendingGroup(val members: Set<String>)

    private enum class GroupChange { Changed, Superseded }

    private fun Map<String, PendingGroup>.members() = mapValues { it.value.members }
}

data class GroupCandidate(val id: String, val name: String, val isPhone: Boolean, val member: Boolean)

/** [known] is false until the server's speakers were read. */
@Immutable
data class PlayOnGroups(
    val speakers: List<SpeakerUi> = emptyList(),
    val leader: SpeakerUi? = null,
    val choices: List<GroupCandidate> = emptyList(),
    val grouped: Boolean = false,
    val known: Boolean = true,
)

// What was [asked] for replaces the server's group; an empty asking is no group.
private fun Map<String, Set<String>>.with(asked: Map<String, Set<String>>): Map<String, Set<String>> =
    this - asked.keys + asked.filterValues { it.isNotEmpty() }

internal fun playOnGroups(
    players: Map<String, Player>,
    asked: Map<String, Set<String>>,
    phoneId: String?,
    listed: List<SpeakerUi>,
    currentId: String,
    known: Boolean = true,
): PlayOnGroups {
    val groups = players.syncGroups().with(asked)
    val members = groups.values.flatten().toSet()
    // A speaker just let go still reports synced_to, so the speaker list leaves it out: it is added at once.
    val freed = players.values
        .filter { it.syncedTo in asked && it.playerId !in members && listed.none { row -> row.id == it.playerId } }
        .filter { it.copy(syncedTo = null).isShownSpeaker(phoneId) }
        .map { SpeakerUi(it.playerId, it.shownName, available = it.available) }
    val (phone, others) = listed.partition { it.isPhone }
    val speakers = (phone + if (freed.isEmpty()) others else (others + freed).sortedBy { it.name.lowercase() })
        .filter { it.id !in members }
        .map { if (it.id in groups || it.id in asked) it.copy(others = groups[it.id].orEmpty().size) else it }
    fun leads(id: String) = choices(players, groups, phoneId, id).isNotEmpty()
    val leaderId = currentId.takeIf { it in groups }
        ?: speakers.firstOrNull { it.id in groups }?.id
        ?: groups.keys.firstOrNull()
        ?: currentId.takeIf(::leads)
        ?: speakers.filterNot { it.isPhone }.sortedByDescending { it.isPlaying }.firstOrNull { leads(it.id) }?.id
        ?: currentId
    val leader = speakers.firstOrNull { it.id == leaderId }
        ?: players[leaderId]?.let { SpeakerUi(it.playerId, it.shownName, isPhone = it.playerId == phoneId) }
    val choices = choices(players, groups, phoneId, leaderId)
    return PlayOnGroups(speakers, leader, choices, grouped = groups.isNotEmpty(), known = known)
}

// Members first, by the group as it stands rather than the ticks, so ticking moves no row; then the
// free speakers the server lets join.
private fun choices(
    players: Map<String, Player>,
    groups: Map<String, Set<String>>,
    phoneId: String?,
    leaderId: String,
): List<GroupCandidate> {
    val leader = players[leaderId]?.takeIf { it.canLeadGroup } ?: return emptyList()
    val members = groups[leaderId].orEmpty()
    val taken = groups.values.flatten() - members
    return (members + leader.canGroupWith - leaderId)
        .mapNotNull { players[it] }
        .filter { it.playerId in members || (it.isFree && it.playerId !in taken) }
        .map { GroupCandidate(it.playerId, it.shownName, it.playerId == phoneId, member = it.playerId in members) }
        .sortedWith(compareBy({ !it.member }, { it.name.lowercase() }))
}

private val Player.isFree: Boolean get() = canPlayAlong && !hideInUi && syncedTo == null

private const val GROUP_CONFIRM_MS = 5_000L
