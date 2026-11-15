package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.MaUser
import io.github.hazymorning.amsel.api.ServerInfo
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectsTest {
    private val server = ServerInfo("s1", "2.10.9", 28)
    private val anna = SessionState.Ready(server, MaUser("u1", "anna"))
    private val ben = SessionState.Ready(server, MaUser("u2", "ben"))

    @Test fun everyConnectReloads() = runTest {
        val states = flowOf(
            SessionState.Waiting(null),
            anna,
            SessionState.Offline("Home"),
            SessionState.Waiting("Home"),
            // A reconnect to the same server: events were missed while away.
            anna,
            // Sign out, then sign in again on the same server: clear() emptied Home and the index.
            SessionState.NeedsSetup,
            ben,
        )
        assertEquals(listOf(anna, anna, ben), states.connects().toList())
    }

    @Test fun stayingConnectedDoesNotReload() = runTest {
        assertEquals(listOf(anna), flowOf(anna, anna, SessionState.Waiting("Home")).connects().toList())
    }
}
