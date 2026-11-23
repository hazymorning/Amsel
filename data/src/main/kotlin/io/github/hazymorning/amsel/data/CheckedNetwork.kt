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
 * The network on which the signed-in server last answered with its own id, kept across restarts of
 * the app until the phone restarts. Only there does a home address surely lead to the household's
 * own machines; on another network it may lead to anyone's.
 */
internal class CheckedNetwork(private val network: PhoneNetwork, private val prefs: PrefsStore, scope: CoroutineScope) {
    private val checkedOn = MutableStateFlow<NetworkKey?>(null)
    private val checking = Mutex()

    /** The preferences hold what [checkedOn] holds, also when a connect and a sign-out meet. */
    private val keeping = Mutex()

    /** The phone is on that network now. */
    val here: StateFlow<Boolean> = combine(network.current, checkedOn, ::matches)
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** As [here], from the networks as they are at this moment. */
    fun isHere(): Boolean = matches(network.current.value, checkedOn.value)

    /** Takes up the network an earlier run of the app kept. */
    fun restore(kept: String?) {
        checkedOn.value = kept?.let(NetworkKey::parse)
    }

    /** The server answered as itself, on the network the phone is on now, once that is known. */
    suspend fun passed() {
        network.current.value?.let { keep(it) }
    }

    /** Another session, or none: no network is checked for it yet. */
    suspend fun forget() = keep(null)

    /**
     * Whether the phone is on that network, asking [answersAsItself] where it is not known yet. One
     * asks at a time, and an answer counts only for the network it came on.
     */
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
            // Known for this run; the next check keeps it again.
        }
    }

    private fun matches(now: NetworkKey?, checked: NetworkKey?) = now != null && now == checked
}
