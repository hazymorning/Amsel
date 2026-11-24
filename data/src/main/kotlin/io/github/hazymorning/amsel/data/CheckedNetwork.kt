package io.github.hazymorning.amsel.data

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The network on which the signed-in server last passed the `server_id` check, kept across app restarts until
 * the phone restarts. Only there does a home address surely reach the household's machines.
 */
internal class CheckedNetwork(private val network: PhoneNetwork, private val prefs: PrefsStore, scope: CoroutineScope) {
    private val checkedOn = MutableStateFlow<NetworkKey?>(null)
    private val checking = Mutex()

    // Keeps the stored value equal to [checkedOn] when a connect and a sign-out race.
    private val keeping = Mutex()

    val here: StateFlow<Boolean> = combine(network.current, checkedOn, ::matches)
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** [here] read from the sources now, without waiting for the flow to update. */
    fun isHere(): Boolean = matches(network.current.value, checkedOn.value)

    fun restore(kept: String?) {
        checkedOn.value = kept?.let(NetworkKey::parse)
    }

    suspend fun passed() {
        network.current.value?.let { keep(it) }
    }

    suspend fun forget() = keep(null)

    suspend fun confirm(answersAsItself: suspend () -> Boolean): Boolean = isHere() || checking.withLock {
        if (isHere()) return@withLock true
        val now = network.current.value ?: return@withLock false
        if (!answersAsItself() || network.current.value != now) return@withLock false
        keep(now)
        true
    }

    private suspend fun keep(key: NetworkKey?) = keeping.withLock {
        if (checkedOn.value == key) return@withLock
        checkedOn.value = key
        try {
            prefs.saveCheckedNetwork(key?.text)
        } catch (e: IOException) {
            // Held in memory for this run; the next check saves it again.
        }
    }

    private fun matches(now: NetworkKey?, checked: NetworkKey?) = now != null && now == checked
}
