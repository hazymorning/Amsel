package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.QueueDj
import io.github.hazymorning.amsel.api.orNullOnFailure
import io.github.hazymorning.amsel.data.ExtrasRepository
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

class QueueDjController(
    private val extras: ExtrasRepository,
    private val messages: MessageController,
    private val scope: CoroutineScope,
    private val nameOf: (queueId: String) -> String,
) {
    private val asked = MutableStateFlow<Map<String, HostChoice>>(emptyMap())

    // One call at a time, in order: each answer is the whole state after it, so the last to arrive
    // must be the last asked.
    private val sending = Mutex()

    // Unconfined, so each change is followed in order where it is made: on other threads a pick could
    // be dropped before the answer replacing it is seen, and the sheet would show no DJ in between.
    val hosts: StateFlow<Map<String, String>> = combine(extras.djs, asked, ::shown)
        .stateIn(scope + Dispatchers.Unconfined, SharingStarted.Eagerly, shown(extras.djs.value, asked.value))

    fun refresh() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sending.withLock { orNullOnFailure { extras.refreshDj() } }
        }
    }

    /** A null [hostId] turns the DJ off. */
    fun set(queueId: String, hostId: String?) {
        if (shown(extras.djs.value, asked.value)[queueId] == hostId) return
        val pick = HostChoice(hostId)
        asked.update { it + (queueId to pick) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sending.withLock {
                val error = try {
                    extras.setDj(queueId, hostId)
                    null
                } catch (e: MaException) {
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

// Compared by identity: a later pick of the same host is still another.
private class HostChoice(val hostId: String?)

private fun shown(djs: Map<String, QueueDj>, asked: Map<String, HostChoice>): Map<String, String> =
    (djs.mapValues { it.value.hostId } - asked.keys) +
        asked.mapNotNull { (queue, pick) -> pick.hostId?.let { queue to it } }

private const val INVALID_DATA = 3

// No Retry can help: the host is gone (3), the DJ plugin is (12), or the user may not use the
// speaker (22). What the server offers is read again instead.
private val FINAL = setOf(
    INVALID_DATA,
    MaCommandException.ERROR_INVALID_COMMAND,
    MaCommandException.ERROR_INSUFFICIENT_PERMISSIONS,
)
