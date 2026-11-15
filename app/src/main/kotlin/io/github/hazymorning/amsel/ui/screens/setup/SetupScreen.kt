package io.github.hazymorning.amsel.ui.screens.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.AppGraph
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.ShowMessages
import io.github.hazymorning.amsel.ui.components.MessageHost
import io.github.hazymorning.amsel.ui.rememberLocalNetworkGate
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Space

/**
 * Setup as one flow: Welcome, Your server, Sign in, with Back going up one page at a time.
 * Home Assistant's sign-in page covers the flow while it is open. Its ViewModel lives while Setup
 * is shown, so signing out later starts again at Welcome.
 */
@Composable
fun SetupRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = context.graph
    val viewModel = viewModel(rememberViewModelStoreOwner()) { setupViewModel(graph, createSavedStateHandle()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ShowMessages(graph.messages, snackbar)

    // On Android 17 every way to a server on the local network needs the permission: the search,
    // a found server and a typed address too (a denial at "Find my server" is asked again). The
    // prompt follows the tap, so the line saying why is already on screen. If it is denied the
    // search finds nothing and the address field appears; an address outside the network works.
    val findServer = rememberLocalNetworkGate(viewModel::findServer)
    val connect = rememberLocalNetworkGate(viewModel::onConnect)
    var clickedServer by rememberSaveable { mutableStateOf<String?>(null) }
    val connectClicked = rememberLocalNetworkGate {
        state.server.servers.firstOrNull { it.id == clickedServer }?.let(viewModel::onServerClick)
    }

    Box(modifier.fillMaxSize()) {
        val homeAssistantUrl = state.homeAssistantUrl
        if (homeAssistantUrl != null) {
            HomeAssistantSignIn(
                url = homeAssistantUrl,
                onCode = viewModel::onHomeAssistantCode,
                onFailed = viewModel::onHomeAssistantFailed,
                onClose = viewModel::onHomeAssistantClose,
            )
        } else {
            BackHandler(enabled = state.step != SetupStep.Welcome, onBack = viewModel::back)
            AnimatedContent(
                targetState = state.step,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { fadeIn(Motion.effects()) togetherWith fadeOut(Motion.effects()) },
                label = "setup step",
            ) { step ->
                when (step) {
                    SetupStep.Welcome -> WelcomeScreen(onFindServer = findServer)

                    SetupStep.Server -> ServerScreen(
                        state = state.server,
                        onBack = viewModel::back,
                        onServerClick = { server ->
                            clickedServer = server.id
                            connectClicked()
                        },
                        onTypeAddress = viewModel::onTypeAddress,
                        onAddressChange = viewModel::onAddressChange,
                        onConnect = connect,
                    )

                    SetupStep.SignIn -> state.signIn?.let { signIn ->
                        SignInScreen(
                            state = signIn,
                            onBack = viewModel::back,
                            onSignInWithHomeAssistant = viewModel::onSignInWithHomeAssistant,
                            onUsernameChange = viewModel::onUsernameChange,
                            onPasswordChange = viewModel::onPasswordChange,
                            onPasswordVisibleChange = viewModel::onPasswordVisibleChange,
                            onSignIn = viewModel::onSignIn,
                        )
                    }
                }
            }
        }
        // Setup sits outside the app shell, so it shows its own messages ("Finish setting up Music
        // Assistant first"), above the keyboard and the system bars.
        MessageHost(
            snackbar,
            Modifier.align(
                Alignment.BottomCenter,
            ).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = Space.Sibling),
        )
    }
}

private fun setupViewModel(graph: AppGraph, handle: SavedStateHandle): SetupViewModel {
    val data = graph.data
    return SetupViewModel(
        handle = handle,
        discover = data.discovery::servers,
        probe = data.session.auth::probe,
        signIn = data.session::signIn,
        homeAssistantUrl = { address -> data.session.auth.homeAssistantUrl(address) },
        completeHomeAssistant = data.session::completeHomeAssistant,
        messages = graph.messages,
    )
}
