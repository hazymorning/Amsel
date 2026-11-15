package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.ConnectionState
import io.github.hazymorning.amsel.api.ServerInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the server last said stops counting only once the socket has tried a while in vain. */
@OptIn(ExperimentalCoroutinesApi::class)
class StalenessTest {
    private val scope = TestScope()
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val stale = scope.staleness(state, STALE_MS)
    private val connected = ConnectionState.Connected(ServerInfo("s1", "2.11.0", 99), null)

    private fun after(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    @Test fun `a drop shorter than the grace changes nothing`() {
        state.value = connected
        after(1)
        state.value = ConnectionState.Connecting
        after(STALE_MS - 1)
        state.value = connected
        after(STALE_MS)
        assertFalse(stale.value)
    }

    @Test fun `trying in vain past the grace is stale until the socket connects, closed too`() {
        state.value = connected
        after(1)
        // A network that comes and goes restarts the tries, not the count.
        state.value = ConnectionState.Offline
        after(STALE_MS / 2)
        state.value = ConnectionState.Connecting
        after(1)
        state.value = ConnectionState.Offline
        after(STALE_MS / 2 - 2)
        assertFalse(stale.value)
        after(1)
        assertTrue(stale.value)
        state.value = ConnectionState.Idle
        after(STALE_MS)
        assertTrue(stale.value)
        state.value = ConnectionState.Connecting
        after(1)
        assertTrue(stale.value)
        state.value = connected
        after(1)
        assertFalse(stale.value)
    }

    @Test fun `a start long after a close counts afresh`() {
        state.value = connected
        after(1)
        state.value = ConnectionState.Idle
        after(STALE_MS * 30)
        state.value = ConnectionState.Connecting
        after(STALE_MS - 1)
        assertFalse(stale.value)
        after(1)
        assertTrue(stale.value)
    }

    private companion object {
        const val STALE_MS = 120_000L
    }
}
