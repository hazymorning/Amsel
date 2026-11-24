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
import io.github.hazymorning.amsel.app.MessageController
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

class SetupViewModel(
    handle: SavedStateHandle,
    private val discover: () -> Flow<List<FoundServer>>,
    private val probe: suspend (ServerAddress) -> ServerProbe,
    private val signIn: suspend (ServerAddress, ServerInfo, username: String, password: String) -> SignInResult,
    private val homeAssistantUrl: suspend (ServerAddress) -> String?,
    private val completeHomeAssistant: suspend (ServerAddress, ServerInfo, code: String) -> SignInResult,
    private val messages: MessageController,
) : ViewModel() {
    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    private var found: List<FoundServer> = emptyList()

    private var chosen: SelectedServer? = null
    private var search: Job? = null
    private var settle: Job? = null
    private var work: Job? = null

    init {
        handle.get<Bundle>(KEPT)?.let { restore(decodeFromSavedState(it)) }
        handle.setSavedStateProvider(KEPT) { encodeToSavedState(kept()) }
    }

    fun findServer() {
        if (_state.value.step != SetupStep.Welcome) return
        found = emptyList()
        _state.update { it.copy(step = SetupStep.Server, server = ServerUiState()) }
        search = viewModelScope.launch {
            // Without the permission or a discovery service the flow ends early, so the field appears at once.
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
            connect(
                ServerCheckRequest(address, connectingTo = server.address, field = server.address, fromList = false),
            )
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

    /** The page can deliver the code twice before it closes; only the first counts. */
    fun onHomeAssistantCode(code: String) {
        val server = chosen ?: return
        if (_state.value.homeAssistantUrl == null) return
        _state.update { it.copy(homeAssistantUrl = null) }
        work = viewModelScope.launch {
            finish(attempt { completeHomeAssistant(server.address, server.info, code) }, SignInError.HomeAssistant)
        }
    }

    fun onHomeAssistantFailed() = leaveHomeAssistant(SignInError.HomeAssistant)

    fun onHomeAssistantClose() = leaveHomeAssistant(error = null)

    private fun leaveHomeAssistant(error: SignInError?) {
        if (_state.value.homeAssistantUrl == null) return
        _state.update { it.copy(homeAssistantUrl = null, signIn = it.signIn?.copy(busy = null, error = error)) }
    }

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
                // Back to the list it came from; a lone or typed server comes back as an editable address,
                // so Back never connects to it again by itself.
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
            // Two servers often answer close together: wait for another before taking this one.
            settle = viewModelScope.launch {
                delay(SETTLE_MS)
                connectLone()
            }
        }
    }

    private fun connectLone() {
        val lone = found.singleOrNull() ?: return
        if (_state.value.server.step == ServerStep.Searching) connect(lone.target(fromList = false))
    }

    private fun showAddress() = updateServer {
        if (step == ServerStep.Searching) copy(step = ServerStep.Address, notFound = true) else this
    }

    private fun connect(target: ServerCheckRequest) {
        stopSearch()
        work?.cancel()
        updateServer { copy(connectingTo = target.connectingTo, error = null) }
        work = viewModelScope.launch {
            when (val check = checkServer(target.address, probe)) {
                is ServerCheck.Ready -> {
                    val server =
                        SelectedServer(
                            target.address,
                            check.probe.info,
                            target.fromList,
                            check.probe.offersHomeAssistant,
                        )
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
                    val error = check.error.of(target)
                    copy(step = ServerStep.Address, address = target.field, connectingTo = null, error = error)
                }

                ServerCheck.NeedsSetup -> {
                    // Stays on the step; a lone server from the search moves to the address step, filled in, to retry.
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

    /** The message can outlive the step: retry only while still on it and idle. */
    private fun connectAgain(target: ServerCheckRequest) {
        val state = _state.value
        if (state.step == SetupStep.Server && !state.server.connecting) connect(target)
    }

    private fun stopSearch() {
        settle?.cancel()
        search?.cancel()
        settle = null
        search = null
    }

    /** On success busy stays set: Sign in shows progress until the app replaces Setup. */
    private fun finish(result: SignInResult, error: SignInError) {
        if (result !is SignInResult.Success) updateSignIn { copy(busy = null, error = error) }
    }

    private fun kept(): SavedSetupState = _state.value.kept(chosen)

    private fun restore(kept: SavedSetupState) {
        chosen = kept.chosen
        kept.restored()?.let { _state.value = it } ?: findServer()
    }

    private inline fun updateServer(crossinline change: ServerUiState.() -> ServerUiState) =
        _state.update { it.copy(server = it.server.change()) }

    private inline fun updateSignIn(crossinline change: SignInUiState.() -> SignInUiState) =
        _state.update { it.copy(signIn = it.signIn?.change()) }

    companion object {
        private const val KEPT = "setup"

        const val SEARCH_MS = 5_000L

        const val SETTLE_MS = 1_000L
    }
}

enum class SetupStep { Welcome, Server, SignIn }

@Immutable
data class SetupUiState(
    val step: SetupStep = SetupStep.Welcome,
    val server: ServerUiState = ServerUiState(),
    /** Kept after Back, so the Sign in page fades out as it was. */
    val signIn: SignInUiState? = null,
    /** Non-null while Home Assistant's sign-in page covers Setup. */
    val homeAssistantUrl: String? = null,
)
