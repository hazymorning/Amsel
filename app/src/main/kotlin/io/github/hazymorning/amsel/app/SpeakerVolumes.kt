package io.github.hazymorning.amsel.app

import androidx.compose.runtime.Immutable
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.membersOf
import io.github.hazymorning.amsel.data.DataGraph
import io.github.hazymorning.amsel.playback.failureText
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One speaker of a group in the volume sheet. */
@Immutable
data class MemberVolume(val id: String, val name: String, val level: Int)

/**
 * The volume of each speaker in a group, beside the group's own (which [Playback] sets). A drag
 * sends at most one level every 150 ms per speaker, and always the last.
 */
class SpeakerVolumes(private val data: DataGraph, private val messages: Messages, private val scope: CoroutineScope) {
    private val wanted = ConcurrentHashMap<String, Int>()
    private val nudge = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (unit in nudge) {
                while (true) {
                    val (id, level) = wanted.entries.firstOrNull()?.toPair() ?: break
                    wanted.remove(id, level)
                    send(id, level)
                    delay(SEND_EVERY_MS)
                }
            }
        }
    }

    /**
     * The speakers [leaderId] plays with whose volume can be set, in the server's order; empty
     * when it plays alone.
     */
    fun members(players: Map<String, Player>, leaderId: String): List<MemberVolume> {
        val members = listOfNotNull(players[leaderId]) + players.membersOf(leaderId)
        if (members.size < 2) return emptyList()
        return members.mapNotNull { member ->
            member.volumeLevel?.takeIf { member.volumeControl != NO_CONTROL }
                ?.let { MemberVolume(member.playerId, member.shownName, it) }
        }
    }

    fun set(playerId: String, level: Int) {
        wanted[playerId] = level.coerceIn(0, 100)
        nudge.trySend(Unit)
    }

    private suspend fun send(playerId: String, level: Int) {
        val error = try {
            data.session.api.setOwnVolume(playerId, level)
            return
        } catch (e: MaException) {
            e
        }
        val name = data.speakers.players.value[playerId]?.shownName.orEmpty()
        messages.show(failureText(error), name, retry = { set(playerId, level) })
    }
}

private const val NO_CONTROL = "none"
private const val SEND_EVERY_MS = 150L
