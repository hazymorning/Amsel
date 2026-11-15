package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.QueueDj
import io.github.hazymorning.amsel.api.orNullOnFailure
import io.github.hazymorning.amsel.data.Extras
import io.github.hazymorning.amsel.playback.failureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The DJ that talks between the songs of each speaker, as its sheet shows and sets it. */
class QueueDjs(
    private val extras: Extras,
    private val messages: Messages,
    private val scope: CoroutineScope,
    /** The speaker's name as Play on shows it, for a sentence about it. */
    private val nameOf: (queueId: String) -> String,
) {
    /** Picks the server has not answered yet, by queue. */
    private val asked = MutableStateFlow<Map<String, Pick>>(emptyMap())

    /**
     * One call at a time, in the order they were made: each answer is the whole state after it, so
     * the last one to arrive has to be the last one asked.
     */
    private val sending = Mutex()

    /**
     * The host that talks on each speaker, by queue id; none where the DJ is off. A pick shows at
     * once. Followed where each change is made, in its order: on threads of their own, a pick could
     * go before the server's answer that replaces it was seen, and the sheet would show no DJ between.
     */
    val hosts: StateFlow<Map<String, String>> = combine(extras.djs, asked, ::shown)
        .stateIn(scope + Dispatchers.Unconfined, SharingStarted.Eagerly, shown(extras.djs.value, asked.value))

    /** Reads the hosts and each speaker's DJ again; what is shown stays where the server cannot be asked. */
    fun refresh() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sending.withLock { orNullOnFailure { extras.refreshDj() } }
        }
    }

    /**
     * Lets [hostId] talk on [queueId], or nobody with null. The server's answer takes the pick's
     * place and is said in a message; a refusal takes the pick back and says why, with Retry where
     * asking again can help.
     */
    fun set(queueId: String, hostId: String?) {
        if (shown(extras.djs.value, asked.value)[queueId] == hostId) return
        val pick = Pick(hostId)
        asked.update { it + (queueId to pick) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sending.withLock {
                val error = try {
                    extras.setDj(queueId, hostId)
                    null
                } catch (e: MaConnectionException) {
                    e
                } catch (e: MaCommandException) {
                    e
                }
                asked.update { if (it[queueId] === pick) it - queueId else it }
                when {
                    error == null -> messages.show(if (hostId != null) R.string.dj_on else R.string.dj_off)

                    error is MaCommandException && error.code in FINAL -> {
                        messages.show(if (error.isPermissionError) failureText(error) else R.string.dj_gone)
                        orNullOnFailure { extras.load() }
                    }

                    else -> messages.show(failureText(error), nameOf(queueId), retry = { set(queueId, hostId) })
                }
            }
        }
    }
}

/** Identity tells one pick from a later one of the same host. */
private class Pick(val hostId: String?)

private fun shown(djs: Map<String, QueueDj>, asked: Map<String, Pick>): Map<String, String> =
    (djs.mapValues { it.value.hostId } - asked.keys) +
        asked.mapNotNull { (queue, pick) -> pick.hostId?.let { queue to it } }

private const val INVALID_DATA = 3

/**
 * What asking again cannot mend: the host is gone (3), the DJ's plugin is (12), or the speaker is
 * not this person's to use (22). What the server offers is read again instead.
 */
private val FINAL = setOf(
    INVALID_DATA,
    MaCommandException.ERROR_INVALID_COMMAND,
    MaCommandException.ERROR_INSUFFICIENT_PERMISSIONS,
)
