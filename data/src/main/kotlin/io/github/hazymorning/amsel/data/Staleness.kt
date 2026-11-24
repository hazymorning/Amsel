package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// True once the socket has tried to connect for [afterMs] in a row; a close keeps it, as nothing new was heard,
// until the socket connects. Connecting and Offline are one phase, so a network that comes and goes does not
// restart the count.
internal fun CoroutineScope.staleness(state: Flow<ConnectionState>, afterMs: Long): StateFlow<Boolean> {
    val stale = MutableStateFlow(false)
    launch {
        state.map(::connectionPhaseOf).distinctUntilChanged().collectLatest { link ->
            when (link) {
                ConnectionPhase.Up -> stale.value = false

                ConnectionPhase.Trying -> {
                    delay(afterMs)
                    stale.value = true
                }

                ConnectionPhase.Closed -> Unit
            }
        }
    }
    return stale.asStateFlow()
}

private enum class ConnectionPhase { Up, Trying, Closed }

private fun connectionPhaseOf(state: ConnectionState): ConnectionPhase = when (state) {
    is ConnectionState.Connected -> ConnectionPhase.Up
    ConnectionState.Connecting, ConnectionState.Offline -> ConnectionPhase.Trying
    ConnectionState.Idle, ConnectionState.SignedOut, ConnectionState.Unsupported -> ConnectionPhase.Closed
}
