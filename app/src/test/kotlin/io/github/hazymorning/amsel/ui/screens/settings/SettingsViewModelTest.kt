package io.github.hazymorning.amsel.ui.screens.settings

import app.cash.turbine.test
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.MaUser
import io.github.hazymorning.amsel.api.ServerAddress
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.app.Messages
import io.github.hazymorning.amsel.data.Prefs
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.ui.theme.ThemeMode
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val server = ServerInfo("s1", "2.10.9", 28, name = "Living room")
    private val annaUser = MaUser("u1", "anna", displayName = "Anna")
    private val anna = SessionState.Ready(server, annaUser)
    private val address = ServerAddress.fromBaseUrl("http://192.168.1.20:8095")
    private val stored = Prefs(serverUrl = "http://192.168.1.20:8095", serverName = "Home", theme = "dark")

    private val session = MutableStateFlow<SessionState>(anna)
    private val user = MutableStateFlow<MaUser?>(annaUser)
    private val prefs = MutableStateFlow<Prefs?>(null)
    private val messages = Messages(MutableStateFlow(true))
    private val savedThemes = mutableListOf<String>()
    private var saveTheme: suspend (String) -> Unit = { savedThemes += it }
    private var signOuts = 0
    private var signOut: suspend () -> Unit = { signOuts++ }

    private fun TestScope.viewModel() = SettingsViewModel(
        session = session,
        address = MutableStateFlow(address),
        user = user,
        prefs = prefs,
        saveTheme = { saveTheme(it) },
        signOutAndForget = { signOut() },
        messages = messages,
        appScope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        appVersion = "0.1.0",
    )

    /** Settings on screen: collects the state the way the screen does. */
    private fun TestScope.shown(vm: SettingsViewModel) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

    @Before fun setUp() = Dispatchers.setMain(main)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun themeRoundTripsThroughTheStoredWord() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.of(it.pref)) }
        assertEquals(listOf("system", "light", "dark"), ThemeMode.entries.map { it.pref })
        assertEquals(ThemeMode.System, ThemeMode.of("sepia"))
    }

    @Test fun showsTheServerAsSetupShowedIt() {
        val state = settingsState(stored, anna, address, annaUser, chosenTheme = null, "0.1.0")
        assertEquals(SettingsUiState("Anna", "Living room", "192.168.1.20", ThemeMode.Dark, "0.1.0"), state)
        val https = ServerAddress.fromBaseUrl("https://music.example.com")
        assertEquals("music.example.com", settingsState(stored, anna, https, annaUser, null, "0.1.0").serverAddress)
        // Ports never appear on screen, not even an unusual one.
        val otherPort = ServerAddress.fromBaseUrl("http://music.local:9000")
        assertEquals("music.local", settingsState(stored, anna, otherPort, annaUser, null, "0.1.0").serverAddress)
    }

    @Test fun beforePrefsAreReadTheThemeIsTheSystems() {
        assertEquals(ThemeMode.System, settingsState(null, anna, address, annaUser, null, "0.1.0").theme)
        assertEquals("Home", serverNameOf(SessionState.Offline("Home")))
        assertEquals("", serverNameOf(SessionState.Waiting(null)))
        assertEquals(
            "anna",
            settingsState(null, anna, address, MaUser("u1", "anna", displayName = " "), null, "0.1.0").userName,
        )
    }

    @Test fun opensOnTheStoredTheme() = runTest {
        prefs.value = stored
        assertEquals(ThemeMode.Dark, viewModel().state.value.theme)
    }

    @Test fun followsSessionAndPrefs() = runTest {
        val vm = viewModel()
        vm.state.test {
            val first = awaitItem()
            assertEquals("Anna", first.userName)
            assertEquals("192.168.1.20", first.serverAddress)
            prefs.emit(stored)
            assertEquals(ThemeMode.Dark, awaitItem().theme)
            user.value = MaUser("u2", "ben")
            assertEquals("ben", awaitItem().userName)
        }
    }

    @Test fun aThemeTapShowsAtOnceAndIsSaved() = runTest {
        prefs.emit(stored)
        val vm = viewModel()
        vm.state.test {
            assertEquals(ThemeMode.Dark, awaitItem().theme)
            vm.onThemeChange(ThemeMode.Light)
            assertEquals(ThemeMode.Light, awaitItem().theme)
            assertEquals(listOf("light"), savedThemes)
            prefs.emit(stored.copy(theme = "light"))
            expectNoEvents()
        }
    }

    @Test fun aThemeThatCannotBeSavedGoesBackAndSaysSo() = runTest {
        prefs.emit(stored)
        saveTheme = { throw IOException("disk full") }
        val vm = viewModel()
        vm.state.test {
            assertEquals(ThemeMode.Dark, awaitItem().theme)
            vm.onThemeChange(ThemeMode.Light)
            testScheduler.runCurrent()
            assertEquals(ThemeMode.Dark, vm.state.value.theme)
            cancelAndIgnoreRemainingEvents()
        }
        val shown = messages.pending.value
        assertEquals(listOf(R.string.settings_save_failed), shown.map { it.text })
        // Retry saves the same choice again.
        saveTheme = { savedThemes += it }
        shown.single().retry?.invoke()
        assertEquals(listOf("light"), savedThemes)
    }

    @Test fun aFailedSaveDoesNotUndoALaterTap() = runTest {
        prefs.emit(stored)
        val first = CompletableDeferred<Unit>()
        saveTheme = { if (it == "light") first.await() else savedThemes += it }
        val vm = viewModel()
        shown(vm)
        vm.onThemeChange(ThemeMode.Light)
        vm.onThemeChange(ThemeMode.System)
        first.completeExceptionally(IOException("disk full"))
        testScheduler.runCurrent()
        assertEquals(ThemeMode.System, vm.state.value.theme)
    }

    @Test fun signOutRunsOnceForADoubleTap() = runTest {
        val gate = CompletableDeferred<Unit>()
        signOut = {
            signOuts++
            gate.await()
        }
        val vm = viewModel()
        vm.onSignOut()
        vm.onSignOut()
        assertEquals(1, signOuts)
        gate.complete(Unit)
        testScheduler.runCurrent()
        // Done: a later sign-out (after signing in again) runs.
        vm.onSignOut()
        assertEquals(2, signOuts)
    }

    @Test fun aFailedSignOutSaysSoWithRetry() = runTest {
        signOut = {
            signOuts++
            throw IOException("disk full")
        }
        val vm = viewModel()
        vm.onSignOut()
        val message = messages.pending.value.single()
        assertEquals(R.string.settings_sign_out_failed, message.text)
        message.retry?.invoke()
        assertEquals(2, signOuts)
        assertEquals(2, messages.pending.value.size)
    }
}
