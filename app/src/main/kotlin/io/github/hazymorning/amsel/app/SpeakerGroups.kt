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

/**
 * The speakers that play in step with another, as Play on shows and changes them: its group step
 * and Ungroup. A change shows at once and stands in for the server's word until that has settled,
 * which takes seconds ([syncGroups]); a refusal takes it back and says so, with Retry.
 */
class SpeakerGroups internal constructor(
    private val players: StateFlow<Map<String, Player>>,
    private val listed: StateFlow<List<SpeakerUi>>,
    private val phoneId: StateFlow<String?>,
    private val nameOf: (String) -> String,
    private val messages: Messages,
    private val scope: CoroutineScope,
    /** How often the server's speakers were read since signing in: none yet while Amsel connects. */
    private val loads: StateFlow<Int> = MutableStateFlow(1),
    private val setMembers: suspend (leaderId: String, add: List<String>, remove: List<String>) -> Unit,
) {
    constructor(
        data: DataGraph,
        playing: Playing,
        phone: PhoneSpeaker,
        messages: Messages,
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

    /** The members asked for and not yet settled, by leader; none for a group let go. */
    private val asked = MutableStateFlow<Map<String, Wish>>(emptyMap())

    /** Commands go out in the order they were asked for: each builds on the one before. */
    private val sending = Mutex()

    /** What Play on shows while [currentId] is the speaker in use. */
    fun playOn(currentId: String): Flow<PlayOnGroups> =
        combine(players, asked, phoneId, listed, loads) { players, asked, phoneId, listed, loads ->
            playOnGroups(players, asked.members(), phoneId, listed, currentId, known = loads > 0)
        }

    /** [playOn] as it stands, for the sheet's first frame. */
    fun playOnNow(currentId: String): PlayOnGroups =
        playOnGroups(players.value, asked.value.members(), phoneId.value, listed.value, currentId, loads.value > 0)

    /** Lets [add] join [leaderId]'s group and [remove] leave it. */
    fun set(leaderId: String, add: List<String>, remove: List<String>) {
        if (add.isEmpty() && remove.isEmpty()) return
        val wish = groups()[leaderId].orEmpty() + add - remove.toSet()
        ask(leaderId, wish, add, remove.toSet()) { set(leaderId, add, remove) }
    }

    /** Lets every speaker that plays along with another go, whichever speaker is in use. */
    fun ungroupAll() = groups().keys.forEach(::letGo)

    private fun letGo(leaderId: String) {
        ask(leaderId, emptySet(), emptyList(), leaving = null) { letGo(leaderId) }
    }

    private fun groups() = players.value.syncGroups().with(asked.value.members())

    /** [members] show as [leaderId]'s at once; a refusal takes them back and says so, with [retry]. */
    private fun ask(
        leaderId: String,
        members: Set<String>,
        add: List<String>,
        leaving: Set<String>?,
        retry: () -> Unit,
    ) {
        val wish = Wish(members)
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

    /**
     * Sends [add] and whoever of [leaving] (everyone, when null) the server names with [leaderId],
     * then follows its word while it changes: a speaker that joined a moment ago is named seconds
     * later, and the server lets it go only once it is. A wish that only adds is done once all are
     * named, any other once the server's word has stood still for [GROUP_CONFIRM_MS], and either
     * when a later wish for the leader takes its place.
     *
     * [joining] were asked to join by the wish this one replaces. Of them the leader's own list
     * counts too, which on 2.11 names them seconds before they say so themselves; of any other
     * it does not ([syncGroups]).
     */
    private suspend fun settle(
        leaderId: String,
        wish: Wish,
        add: List<String>,
        leaving: Set<String>?,
        joining: Set<String>,
    ) {
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
                        asked[leaderId] !== wish -> Word.Superseded
                        members(players) != named -> Word.Changed
                        else -> null
                    }
                }.filterNotNull().first()
            }
            if (word != Word.Changed) return
        }
    }

    /** One asking for a leader's members: a later one for the same members is still another. */
    private class Wish(val members: Set<String>)

    private enum class Word { Changed, Superseded }

    private fun Map<String, Wish>.members() = mapValues { it.value.members }
}

/** A speaker that can join a group, and whether it is in it. */
data class GroupChoice(val id: String, val name: String, val isPhone: Boolean, val member: Boolean)

/**
 * Play on's speakers, a group as one, and what its header and group step need. [leader] is whom
 * the group step is for: the leader of the group that exists (the speaker in use's, else the first
 * in the list), else the speaker in use, or where it cannot lead one (This phone, mostly) the first
 * speaker that can, one that plays first. [choices] can play along with it; none when none can lead.
 * [known] is false while Amsel has not read the server's speakers yet.
 */
@Immutable
data class PlayOnGroups(
    val speakers: List<SpeakerUi> = emptyList(),
    val leader: SpeakerUi? = null,
    val choices: List<GroupChoice> = emptyList(),
    val grouped: Boolean = false,
    val known: Boolean = true,
)

/** The server's groups with what was [asked] for in their place; an empty asking is no group. */
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
    // A speaker just let go still says it plays along, so the list leaves it out: it shows at once.
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

/**
 * Who can play along with [leaderId]: its members first, as the group stands, so ticking and
 * unticking moves nothing, then the speakers the server lets join it that are free.
 */
private fun choices(
    players: Map<String, Player>,
    groups: Map<String, Set<String>>,
    phoneId: String?,
    leaderId: String,
): List<GroupChoice> {
    val leader = players[leaderId]?.takeIf { it.canLeadGroup } ?: return emptyList()
    val members = groups[leaderId].orEmpty()
    val taken = groups.values.flatten() - members
    return (members + leader.canGroupWith - leaderId)
        .mapNotNull { players[it] }
        .filter { it.playerId in members || (it.isFree && it.playerId !in taken) }
        .map { GroupChoice(it.playerId, it.shownName, it.playerId == phoneId, member = it.playerId in members) }
        .sortedWith(compareBy({ !it.member }, { it.name.lowercase() }))
}

/** Shown in Play on, and in no group as far as it says itself. */
private val Player.isFree: Boolean get() = canPlayAlong && !hideInUi && syncedTo == null

/** How long the server's word on a group may stand still before it is taken as final. */
private const val GROUP_CONFIRM_MS = 5_000L
