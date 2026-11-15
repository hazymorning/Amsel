package io.github.hazymorning.amsel.ui.screens.setup

import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.ServerAddress
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.api.ServerProbe
import io.github.hazymorning.amsel.api.SignInResult
import io.github.hazymorning.amsel.app.Messages
import io.github.hazymorning.amsel.app.retryWhileOpen
import io.github.hazymorning.amsel.data.FoundServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The whole of Setup: the discovery run, the checked server, the typed fields and what is busy.
 * It outlives rotation, so turning the phone never starts the search again; the page and what was
 * typed (never the password) also outlive the process. Taps change the state at once; the network
 * answers behind them.
 */
class SetupViewModel(
    handle: SavedStateHandle,
    private val discover: () -> Flow<List<FoundServer>>,
    private val probe: suspend (ServerAddress) -> ServerProbe,
    private val signIn: suspend (ServerAddress, ServerInfo, username: String, password: String) -> SignInResult,
    private val homeAssistantUrl: suspend (ServerAddress) -> String?,
    private val completeHomeAssistant: suspend (ServerAddress, ServerInfo, code: String) -> SignInResult,
    private val messages: Messages,
) : ViewModel() {
    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    /** Servers that answered, in the order they answered. */
    private var found: List<FoundServer> = emptyList()

    /** The server Sign in is for. */
    private var chosen: Chosen? = null
    private var search: Job? = null
    private var settle: Job? = null
    private var work: Job? = null

    init {
        handle.get<Bundle>(KEPT)?.let { restore(decodeFromSavedState(it)) }
        handle.setSavedStateProvider(KEPT) { encodeToSavedState(kept()) }
    }

    /** "Find my server": look on the network for [SEARCH_MS]. */
    fun findServer() {
        if (_state.value.step != SetupStep.Welcome) return
        found = emptyList()
        _state.update { it.copy(step = SetupStep.Server, server = ServerUiState()) }
        search = viewModelScope.launch {
            // No permission or no network service: the flow ends early, and the field appears at once.
            withTimeoutOrNull(SEARCH_MS) { discover().catch { }.collect(::onFound) }
            if (found.isEmpty()) showAddress() else connectLone()
        }
    }

    fun onServerClick(server: FoundServerUi) {
        if (_state.value.server.connecting) return
        found.firstOrNull { it.info.serverId == server.id }?.let { connect(it.target(fromList = true)) }
    }

    fun onTypeAddress() = updateServer { copy(step = ServerStep.Address, error = null) }

    fun onAddressChange(address: String) = updateServer { copy(address = address, error = null) }

    fun onConnect() {
        val server = _state.value.server
        if (!server.canConnect) return
        val address = ServerAddress.parse(server.address)
        if (address == null) {
            updateServer { copy(error = ServerError.Unreachable) }
        } else {
            connect(Target(address, connectingTo = server.address, field = server.address, fromList = false))
        }
    }

    fun onUsernameChange(username: String) = updateSignIn { copy(username = username, error = null) }

    fun onPasswordChange(password: String) = updateSignIn { copy(password = password, error = null) }

    fun onPasswordVisibleChange(visible: Boolean) = updateSignIn { copy(passwordVisible = visible) }

    fun onSignIn() {
        val form = _state.value.signIn ?: return
        val server = chosen ?: return
        if (!form.canSignIn) return
        updateSignIn { copy(busy = SignInMethod.Password, error = null) }
        work = viewModelScope.launch {
            val result = attempt { signIn(server.address, server.info, form.username.trim(), form.password) }
            finish(result, result.error)
        }
    }

    fun onSignInWithHomeAssistant() {
        val server = chosen ?: return
        if (_state.value.signIn?.busy != null) return
        updateSignIn { copy(busy = SignInMethod.HomeAssistant, error = null) }
        work = viewModelScope.launch {
            val url = homeAssistantUrl(server.address)
            if (url == null) {
                updateSignIn { copy(busy = null, error = SignInError.HomeAssistant) }
            } else {
                _state.update { it.copy(homeAssistantUrl = url) }
            }
        }
    }

    /**
     * Home Assistant's page sent back Music Assistant's sign-in code: Sign in shows progress
     * meanwhile. The page can call twice before it closes; only its first answer counts.
     */
    fun onHomeAssistantCode(code: String) {
        val server = chosen ?: return
        if (_state.value.homeAssistantUrl == null) return
        _state.update { it.copy(homeAssistantUrl = null) }
        work = viewModelScope.launch {
            finish(attempt { completeHomeAssistant(server.address, server.info, code) }, SignInError.HomeAssistant)
        }
    }

    /** Home Assistant's page returned without a code. */
    fun onHomeAssistantFailed() = leaveHomeAssistant(SignInError.HomeAssistant)

    /** Closed on purpose (the close glyph or Back), which is not an error. */
    fun onHomeAssistantClose() = leaveHomeAssistant(error = null)

    private fun leaveHomeAssistant(error: SignInError?) {
        if (_state.value.homeAssistantUrl == null) return
        _state.update { it.copy(homeAssistantUrl = null, signIn = it.signIn?.copy(busy = null, error = error)) }
    }

    /** One step up: Sign in to Your server, the address field to the list, Your server to Welcome. */
    fun back() {
        val state = _state.value
        when (state.step) {
            SetupStep.Welcome -> Unit

            SetupStep.Server -> {
                work?.cancel()
                if (state.server.step == ServerStep.Address && found.size > 1) {
                    updateServer { copy(step = ServerStep.Choose, connectingTo = null, error = null) }
                } else {
                    stopSearch()
                    found = emptyList()
                    _state.update { it.copy(step = SetupStep.Welcome, server = ServerUiState()) }
                }
            }

            SetupStep.SignIn -> {
                work?.cancel()
                val server = chosen
                // Back to the list it came from; a server found alone or typed comes back as an editable
                // address, so Back never connects to it again by itself.
                val next = if (server?.fromList == true && found.size > 1) {
                    state.server.copy(step = ServerStep.Choose)
                } else {
                    state.server.copy(
                        step = ServerStep.Address,
                        address = state.server.address.ifBlank { server?.address?.let(::typedForm).orEmpty() },
                    )
                }
                _state.update {
                    it.copy(
                        step = SetupStep.Server,
                        homeAssistantUrl = null,
                        server = next.copy(connectingTo = null, error = null),
                    )
                }
            }
        }
    }

    private fun onFound(servers: List<FoundServer>) {
        found = servers
        updateServer {
            val choose = step == ServerStep.Searching && servers.size > 1
            copy(servers = servers.map { it.toUi() }, step = if (choose) ServerStep.Choose else step)
        }
        if (servers.size > 1) {
            settle?.cancel()
        } else if (servers.size == 1 && settle == null) {
            // Wait a moment for a second server before taking this one; two answer close together.
            settle = viewModelScope.launch {
                delay(SETTLE_MS)
                connectLone()
            }
        }
    }

    /** While still searching, connects to the one server that answered. */
    private fun connectLone() {
        val lone = found.singleOrNull() ?: return
        if (_state.value.server.step == ServerStep.Searching) connect(lone.target(fromList = false))
    }

    /** While still searching, gives up and shows the address field. */
    private fun showAddress() = updateServer {
        if (step == ServerStep.Searching) copy(step = ServerStep.Address, notFound = true) else this
    }

    /** Checks [target] and opens Sign in; on failure the address step shows why. */
    private fun connect(target: Target) {
        stopSearch()
        work?.cancel()
        updateServer { copy(connectingTo = target.connectingTo, error = null) }
        work = viewModelScope.launch {
            when (val check = checkServer(target.address, probe)) {
                is ServerCheck.Ready -> {
                    val server =
                        Chosen(target.address, check.probe.info, target.fromList, check.probe.offersHomeAssistant)
                    chosen = server
                    _state.update {
                        it.copy(
                            step = SetupStep.SignIn,
                            server = it.server.copy(connectingTo = null),
                            signIn = server.signInState(),
                            homeAssistantUrl = null,
                        )
                    }
                }

                is ServerCheck.Failed -> updateServer {
                    copy(step = ServerStep.Address, address = target.field, connectingTo = null, error = check.error)
                }

                ServerCheck.NeedsSetup -> {
                    // Stay put; a lone server found while searching gets its address filled in to retry.
                    updateServer {
                        if (step == ServerStep.Searching) {
                            copy(step = ServerStep.Address, address = target.field, connectingTo = null)
                        } else {
                            copy(connectingTo = null)
                        }
                    }
                    messages.show(
                        R.string.setup_finish_setup,
                        retry = retryWhileOpen(messages) {
                            connectAgain(target)
                        },
                    )
                }
            }
        }
    }

    /** Retry from the message, unless the person has moved on meanwhile. */
    private fun connectAgain(target: Target) {
        val state = _state.value
        if (state.step == SetupStep.Server && !state.server.connecting) connect(target)
    }

    private fun stopSearch() {
        settle?.cancel()
        search?.cancel()
        settle = null
        search = null
    }

    /** Signed in: Sign in keeps showing progress until the app takes over. */
    private fun finish(result: SignInResult, error: SignInError) {
        if (result !is SignInResult.Success) updateSignIn { copy(busy = null, error = error) }
    }

    private fun kept(): Kept = _state.value.kept(chosen)

    private fun restore(kept: Kept) {
        chosen = kept.chosen
        // The search ended with the process: look again.
        kept.restored()?.let { _state.value = it } ?: findServer()
    }

    private inline fun updateServer(crossinline change: ServerUiState.() -> ServerUiState) =
        _state.update { it.copy(server = it.server.change()) }

    private inline fun updateSignIn(crossinline change: SignInUiState.() -> SignInUiState) =
        _state.update { it.copy(signIn = it.signIn?.change()) }

    companion object {
        private const val KEPT = "setup"

        /** How long to look on the network before offering the address field. */
        const val SEARCH_MS = 5_000L

        /** How long a lone server waits for a second one before it is taken. */
        const val SETTLE_MS = 1_000L
    }
}

enum class SetupStep { Welcome, Server, SignIn }

/** Everything Setup shows: the page on screen, and the state of each page. */
@Immutable
data class SetupUiState(
    val step: SetupStep = SetupStep.Welcome,
    val server: ServerUiState = ServerUiState(),
    /** Sign in for the checked server; kept after Back so the page can fade out as it was. */
    val signIn: SignInUiState? = null,
    /** Home Assistant's sign-in page, covering Setup while it is open. */
    val homeAssistantUrl: String? = null,
)
