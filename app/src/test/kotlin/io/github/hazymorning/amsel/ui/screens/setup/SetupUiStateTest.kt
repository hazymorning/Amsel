package io.github.hazymorning.amsel.ui.screens.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupUiStateTest {
    private val signIn = SignInUiState(serverName = "Music Assistant", serverAddress = "192.168.1.20")

    @Test fun serverSetupStartsBySearching() {
        val state = ServerUiState()
        assertEquals(ServerStep.Searching, state.step)
        assertFalse(state.connecting)
    }

    @Test fun connectNeedsAnAddress() {
        assertFalse(ServerUiState(step = ServerStep.Address).canConnect)
        assertFalse(ServerUiState(step = ServerStep.Address, address = "   ").canConnect)
        assertTrue(ServerUiState(step = ServerStep.Address, address = "music.local").canConnect)
    }

    @Test fun connectIsNotOfferedTwice() {
        val state = ServerUiState(step = ServerStep.Address, address = "music.local", connectingTo = "music.local")
        assertTrue(state.connecting)
        assertFalse(state.canConnect)
    }

    @Test fun signInNeedsBothFields() {
        assertFalse(signIn.canSignIn)
        assertFalse(signIn.copy(username = "anna").canSignIn)
        assertFalse(signIn.copy(username = " ", password = "blackbird").canSignIn)
        assertTrue(signIn.copy(username = "anna", password = "blackbird").canSignIn)
    }

    @Test fun nothingIsEditableWhileSigningIn() {
        val filled = signIn.copy(username = "anna", password = "blackbird")
        listOf(SignInMethod.Password, SignInMethod.HomeAssistant).forEach { method ->
            val busy = filled.copy(busy = method)
            assertFalse(busy.canSignIn)
            assertFalse(busy.editable)
        }
        assertTrue(filled.editable)
    }

    @Test fun aWrongPasswordCanBeRetried() {
        val state = signIn.copy(username = "anna", password = "blackbird", error = SignInError.WrongPassword)
        assertTrue(state.canSignIn)
    }
}
