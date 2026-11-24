package io.github.hazymorning.amsel.ui.screens.setup

import androidx.lifecycle.SavedStateHandle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.AuthProvider
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.ServerAddress
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.api.ServerProbe
import io.github.hazymorning.amsel.api.SignInResult
import io.github.hazymorning.amsel.app.MessageController
import io.github.hazymorning.amsel.data.FoundServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val messages = MessageController(MutableStateFlow(true))
    private val probed = mutableListOf<ServerAddress>()
    private val signedIn = mutableListOf<Pair<String, String>>()
    private val codes = mutableListOf<String>()

    private val kitchen = found("k1", "Music Assistant", "http://192.168.1.20:8095")
    private val studio = found("s1", "Music Assistant (Studio)", "http://192.168.1.42:8095")
    private val musicLocal = requireNotNull(ServerAddress.parse("music.local"))

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    @Test fun `one server connects by itself after a moment`() = runTest(dispatcher) {
        val vm = setup(discover = answering(300L to kitchen), probe = { probeOf(kitchen.info) })
        vm.findServer()
        advanceTimeBy(500)
        assertEquals(SetupStep.Server, vm.state.value.step)
        assertEquals(ServerStep.Searching, vm.state.value.server.step)
        assertTrue(probed.isEmpty())

        advanceTimeBy(SetupViewModel.SETTLE_MS)
        runCurrent()
        assertEquals(listOf(kitchen.address), probed)
        assertEquals(SetupStep.SignIn, vm.state.value.step)
        assertEquals("Music Assistant", vm.state.value.signIn?.serverName)
        assertEquals("192.168.1.20", vm.state.value.signIn?.serverAddress)
        assertTrue(currentTime < SetupViewModel.SEARCH_MS)
    }

    @Test fun `several servers are listed and the tapped one is checked`() = runTest(dispatcher) {
        val vm = setup(discover = answering(300L to kitchen, 600L to studio), probe = { probeOf(studio.info) })
        vm.findServer()
        advanceTimeBy(700)
        val listed = vm.state.value.server
        assertEquals(ServerStep.Choose, listed.step)
        assertEquals(listOf("192.168.1.20", "192.168.1.42"), listed.servers.map { it.address })

        advanceTimeBy(SetupViewModel.SEARCH_MS)
        assertEquals(ServerStep.Choose, vm.state.value.server.step)
        assertTrue(probed.isEmpty())

        vm.onServerClick(listed.servers[1])
        assertEquals("s1", vm.state.value.server.connectingTo)
        runCurrent()
        assertEquals(listOf(studio.address), probed)
        assertEquals("Music Assistant (Studio)", vm.state.value.signIn?.serverName)

        vm.back()
        assertEquals(SetupStep.Server, vm.state.value.step)
        assertEquals(ServerStep.Choose, vm.state.value.server.step)
        assertNull(vm.state.value.server.connectingTo)
    }

    @Test fun `nothing found shows the address field after five seconds`() = runTest(dispatcher) {
        val vm = setup(discover = answering())
        vm.findServer()
        advanceTimeBy(SetupViewModel.SEARCH_MS - 100)
        assertEquals(ServerStep.Searching, vm.state.value.server.step)
        advanceTimeBy(200)
        assertEquals(ServerStep.Address, vm.state.value.server.step)
        assertEquals("", vm.state.value.server.address)
        assertTrue(vm.state.value.server.notFound)
    }

    @Test fun `asking for the address field does not say nothing was found`() = runTest(dispatcher) {
        val vm = setup(discover = answering(300L to kitchen, 600L to studio))
        vm.findServer()
        advanceTimeBy(700)
        vm.onTypeAddress()
        assertEquals(ServerStep.Address, vm.state.value.server.step)
        assertFalse(vm.state.value.server.notFound)
    }

    @Test fun `a search that cannot start shows the address field at once`() = runTest(dispatcher) {
        val vm = setup(discover = { flow { throw SecurityException("no local network") } })
        vm.findServer()
        runCurrent()
        assertEquals(ServerStep.Address, vm.state.value.server.step)
        assertTrue(vm.state.value.server.notFound)
    }

    @Test fun `typing something that is not an address says so without connecting`() = runTest(dispatcher) {
        val vm = atAddress()
        vm.onAddressChange("music local")
        vm.onConnect()
        assertEquals(ServerError.Unreachable, vm.state.value.server.error)
        assertFalse(vm.state.value.server.connecting)
        assertTrue(probed.isEmpty())
        vm.onAddressChange("music.local")
        assertNull(vm.state.value.server.error)
    }

    @Test fun `a name typed without a scheme is tried over https only, and asks for http when it fails`() =
        runTest(dispatcher) {
            val vm = atAddress(probe = { throw MaConnectionException("refused") })
            vm.onAddressChange("music.example.com")
            vm.onConnect()
            runCurrent()
            assertEquals(listOf(ServerAddress.parse("https://music.example.com")), probed)
            assertEquals(ServerError.HttpsFailed, vm.state.value.server.error)
            assertEquals("music.example.com", vm.state.value.server.address)
        }

    @Test fun `a typed address is checked until it answers as a supported Music Assistant`() = runTest(dispatcher) {
        val answers = ArrayDeque<() -> ServerProbe>(
            listOf(
                { throw MaConnectionException("refused") },
                { probeOf(info("m1", "Living room", schema = 64)) },
                { probeOf(info("m1", "Living room"), homeAssistant = true) },
            ),
        )
        val vm = atAddress(probe = { answers.removeFirst()() })
        vm.onAddressChange("music.local")
        vm.onConnect()
        assertEquals("music.local", vm.state.value.server.connectingTo)
        runCurrent()
        assertEquals(ServerError.Unreachable, vm.state.value.server.error)
        assertFalse(vm.state.value.server.connecting)

        vm.onConnect()
        runCurrent()
        assertEquals(ServerError.TooOld, vm.state.value.server.error)

        vm.onConnect()
        runCurrent()
        assertEquals(List(3) { musicLocal }, probed)
        val signIn = vm.state.value.signIn
        assertEquals(SetupStep.SignIn, vm.state.value.step)
        assertEquals("Living room", signIn?.serverName)
        assertEquals("music.local", signIn?.serverAddress)
        assertEquals(true, signIn?.offersHomeAssistant)

        vm.back()
        assertEquals(ServerStep.Address, vm.state.value.server.step)
        assertEquals("music.local", vm.state.value.server.address)
    }

    @Test fun `a server that is not set up says so and stays`() = runTest(dispatcher) {
        val vm = atAddress(probe = { throw MaCommandException("connection", 503, "Setup required") })
        vm.onAddressChange("music.local")
        vm.onConnect()
        runCurrent()
        assertEquals(SetupStep.Server, vm.state.value.step)
        assertEquals(ServerStep.Address, vm.state.value.server.step)
        assertNull(vm.state.value.server.error)
        assertFalse(vm.state.value.server.connecting)
        val shown = messages.pending.value
        assertEquals(listOf(R.string.setup_finish_setup), shown.map { it.text })

        shown.single().retry?.invoke()
        runCurrent()
        assertEquals(2, probed.size)
    }

    @Test fun `a lone server that does not answer opens the address field with its address`() = runTest(dispatcher) {
        val vm = setup(discover = answering(100L to kitchen))
        vm.findServer()
        advanceTimeBy(SetupViewModel.SEARCH_MS)
        val server = vm.state.value.server
        assertEquals(ServerStep.Address, server.step)
        assertEquals("192.168.1.20", server.address)
        assertEquals(ServerError.Unreachable, server.error)
    }

    @Test fun `password sign-in shows each outcome in plain words, then finishes`() = runTest(dispatcher) {
        val results =
            ArrayDeque(listOf(SignInResult.WrongPassword, SignInResult.Failed, SignInResult.Success("t")))
        val vm = atSignIn(signIn = { results.removeFirst() })
        vm.onUsernameChange("anna ")
        vm.onPasswordChange("blackbird")
        vm.onSignIn()
        assertEquals(SignInMethod.Password, vm.state.value.signIn?.busy)
        runCurrent()
        assertEquals(SignInError.WrongPassword, vm.state.value.signIn?.error)
        assertNull(vm.state.value.signIn?.busy)

        vm.onPasswordChange("blackbird2")
        assertNull(vm.state.value.signIn?.error)
        vm.onSignIn()
        runCurrent()
        assertEquals(SignInError.Unreachable, vm.state.value.signIn?.error)

        vm.onSignIn()
        runCurrent()
        assertEquals(SignInMethod.Password, vm.state.value.signIn?.busy)
        assertNull(vm.state.value.signIn?.error)
        assertEquals("anna" to "blackbird2", signedIn.last())
    }

    @Test fun `Home Assistant opens its page and finishes with the code`() = runTest(dispatcher) {
        val urls =
            ArrayDeque(listOf(null, "https://ha.example.com/auth/authorize", "https://ha.example.com/auth/authorize"))
        val vm = atSignIn(homeAssistantUrl = { urls.removeFirst() })
        vm.onSignInWithHomeAssistant()
        assertEquals(SignInMethod.HomeAssistant, vm.state.value.signIn?.busy)
        runCurrent()
        assertEquals(SignInError.HomeAssistant, vm.state.value.signIn?.error)
        assertNull(vm.state.value.homeAssistantUrl)

        vm.onSignInWithHomeAssistant()
        runCurrent()
        assertNotNull(vm.state.value.homeAssistantUrl)
        vm.onHomeAssistantCode("ma-session-token")
        assertNull(vm.state.value.homeAssistantUrl)
        assertEquals(SignInMethod.HomeAssistant, vm.state.value.signIn?.busy)
        runCurrent()
        assertEquals(listOf("ma-session-token"), codes)
        assertEquals(SignInMethod.HomeAssistant, vm.state.value.signIn?.busy)
    }

    @Test fun `closing Home Assistant's page returns to Sign in without an error`() = runTest(dispatcher) {
        val vm = atSignIn(homeAssistantUrl = { "https://ha.example.com/auth/authorize" })
        vm.onSignInWithHomeAssistant()
        runCurrent()
        assertNotNull(vm.state.value.homeAssistantUrl)
        vm.onHomeAssistantClose()
        assertNull(vm.state.value.homeAssistantUrl)
        assertNull(vm.state.value.signIn?.busy)
        assertNull(vm.state.value.signIn?.error)
        assertTrue(codes.isEmpty())
    }

    @Test fun `Home Assistant's page returning without a code says signing in didn't finish`() = runTest(dispatcher) {
        val vm = atSignIn(homeAssistantUrl = { "https://ha.example.com/auth/authorize" })
        vm.onSignInWithHomeAssistant()
        runCurrent()
        vm.onHomeAssistantFailed()
        assertNull(vm.state.value.homeAssistantUrl)
        assertNull(vm.state.value.signIn?.busy)
        assertEquals(SignInError.HomeAssistant, vm.state.value.signIn?.error)

        vm.onSignInWithHomeAssistant()
        assertNull(vm.state.value.signIn?.error)
        runCurrent()
        vm.onHomeAssistantClose()
        assertNull(vm.state.value.signIn?.error)
    }

    @Test fun `back from a server found alone never connects to it again by itself`() = runTest(dispatcher) {
        var listening = false
        val discover = {
            flow {
                listening = true
                try {
                    emit(listOf(kitchen))
                    awaitCancellation()
                } finally {
                    listening = false
                }
            }
        }
        val vm = setup(discover = discover, probe = { probeOf(kitchen.info) })
        vm.findServer()
        advanceTimeBy(SetupViewModel.SETTLE_MS + 100)
        assertEquals(SetupStep.SignIn, vm.state.value.step)
        assertFalse(listening)

        vm.back()
        advanceTimeBy(SetupViewModel.SEARCH_MS)
        assertEquals(SetupStep.Server, vm.state.value.step)
        assertEquals(ServerStep.Address, vm.state.value.server.step)
        assertEquals("192.168.1.20", vm.state.value.server.address)
        assertEquals(1, probed.size)

        vm.back()
        assertEquals(SetupStep.Welcome, vm.state.value.step)
        vm.findServer()
        runCurrent()
        assertTrue(listening)
        vm.back()
        runCurrent()
        assertFalse(listening)
    }

    @Test fun `back while a server is checked is never undone`() = runTest(dispatcher) {
        val probe: suspend (ServerAddress) -> ServerProbe = { awaitCancellation() }
        val vm = setup(discover = answering(100L to kitchen, 200L to studio), probe = probe)
        vm.findServer()
        advanceTimeBy(300)
        vm.onServerClick(vm.state.value.server.servers[0])
        runCurrent()
        vm.back()
        runCurrent()
        assertEquals(SetupStep.Welcome, vm.state.value.step)
        assertNull(vm.state.value.signIn)

        vm.findServer()
        advanceTimeBy(300)
        vm.onTypeAddress()
        vm.onAddressChange("music.local")
        vm.onConnect()
        runCurrent()
        vm.back()
        runCurrent()
        assertEquals(ServerStep.Choose, vm.state.value.server.step)
        assertNull(vm.state.value.server.error)
    }

    @Test fun `Home Assistant's page answers once`() = runTest(dispatcher) {
        val vm = atSignIn(homeAssistantUrl = { "https://ha.example.com/auth/authorize" })
        vm.onSignInWithHomeAssistant()
        runCurrent()
        vm.onHomeAssistantCode("ma-session-token")
        vm.onHomeAssistantCode("ma-session-token")
        vm.onHomeAssistantFailed()
        vm.onHomeAssistantClose()
        assertEquals(SignInMethod.HomeAssistant, vm.state.value.signIn?.busy)
        assertNull(vm.state.value.signIn?.error)
        runCurrent()
        assertEquals(listOf("ma-session-token"), codes)
        assertEquals(SignInMethod.HomeAssistant, vm.state.value.signIn?.busy)
    }

    @Test fun `back from the address field returns to the list`() = runTest(dispatcher) {
        val vm = setup(discover = answering(100L to kitchen, 200L to studio))
        vm.findServer()
        advanceTimeBy(300)
        vm.onTypeAddress()
        assertEquals(ServerStep.Address, vm.state.value.server.step)
        vm.back()
        assertEquals(ServerStep.Choose, vm.state.value.server.step)
        vm.back()
        assertEquals(SetupStep.Welcome, vm.state.value.step)
    }

    private fun TestScope.atAddress(
        probe: suspend (ServerAddress) -> ServerProbe = {
            error("unused")
        },
    ): SetupViewModel {
        val vm = setup(discover = { flow { throw SecurityException("no local network") } }, probe = probe)
        vm.findServer()
        runCurrent()
        return vm
    }

    private fun TestScope.atSignIn(
        signIn: suspend () -> SignInResult = { SignInResult.Success("t") },
        homeAssistantUrl: suspend () -> String? = { null },
    ): SetupViewModel {
        val vm = SetupViewModel(
            handle = SavedStateHandle(),
            discover = { flow { throw SecurityException("no local network") } },
            probe = { probeOf(info("m1", "Living room"), homeAssistant = true) },
            signIn = { _, _, username, password ->
                signedIn += username to password
                signIn()
            },
            homeAssistantUrl = { homeAssistantUrl() },
            completeHomeAssistant = { _, _, code ->
                codes += code
                SignInResult.Success("t")
            },
            messages = messages,
        )
        vm.findServer()
        runCurrent()
        vm.onAddressChange("music.local")
        vm.onConnect()
        runCurrent()
        assertEquals(SetupStep.SignIn, vm.state.value.step)
        return vm
    }

    private fun setup(
        discover: () -> Flow<List<FoundServer>>,
        probe: suspend (ServerAddress) -> ServerProbe = { throw MaConnectionException("refused") },
    ) = SetupViewModel(
        handle = SavedStateHandle(),
        discover = discover,
        probe = { address ->
            probed += address
            probe(address)
        },
        signIn = { _, _, _, _ -> SignInResult.Success("t") },
        homeAssistantUrl = { null },
        completeHomeAssistant = { _, _, _ -> SignInResult.Success("t") },
        messages = messages,
    )

    /** Each server answers at its time after the search starts; the flow then stays open, as discovery does. */
    private fun answering(vararg answers: Pair<Long, FoundServer>): () -> Flow<List<FoundServer>> = {
        flow {
            emit(emptyList())
            var elapsed = 0L
            val list = mutableListOf<FoundServer>()
            for ((at, server) in answers) {
                delay(at - elapsed)
                elapsed = at
                list += server
                emit(list.toList())
            }
            awaitCancellation()
        }
    }

    private fun found(id: String, name: String, url: String) =
        FoundServer(requireNotNull(ServerAddress.fromBaseUrl(url)), info(id, name))

    private fun info(id: String, name: String, schema: Int = 99) =
        ServerInfo(serverId = id, serverVersion = "2.11.0", schemaVersion = schema, name = name)

    private fun probeOf(info: ServerInfo, homeAssistant: Boolean = false) = ServerProbe(
        info,
        listOfNotNull(
            AuthProvider("builtin"),
            AuthProvider("homeassistant").takeIf { homeAssistant },
        ),
    )
}
