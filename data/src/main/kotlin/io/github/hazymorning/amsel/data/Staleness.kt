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

/**
 * Whether what the server last said is out of date: the socket has tried for [afterMs] in a row
 * without connecting. A close keeps it so, as nothing new was heard, until the socket connects.
 * Going from Connecting to Offline and back is one try, so a network that keeps coming and going
 * does not start the count again.
 */
internal fun CoroutineScope.staleness(state: Flow<ConnectionState>, afterMs: Long): StateFlow<Boolean> {
    val stale = MutableStateFlow(false)
    launch {
        state.map(::linkOf).distinctUntilChanged().collectLatest { link ->
            when (link) {
                Link.Up -> stale.value = false

                Link.Trying -> {
                    delay(afterMs)
                    stale.value = true
                }

                Link.Closed -> Unit
            }
        }
    }
    return stale.asStateFlow()
}

private enum class Link { Up, Trying, Closed }

private fun linkOf(state: ConnectionState): Link = when (state) {
    is ConnectionState.Connected -> Link.Up
    ConnectionState.Connecting, ConnectionState.Offline -> Link.Trying
    ConnectionState.Idle, ConnectionState.SignedOut, ConnectionState.Unsupported -> Link.Closed
}
