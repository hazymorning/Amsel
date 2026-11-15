package io.github.hazymorning.amsel.ui.screens.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.components.GroupLabel
import io.github.hazymorning.amsel.ui.components.LabelledField
import io.github.hazymorning.amsel.ui.components.ListRow
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.Spinner
import io.github.hazymorning.amsel.ui.components.WaitingState
import io.github.hazymorning.amsel.ui.components.headerToRow
import io.github.hazymorning.amsel.ui.components.inkStart
import io.github.hazymorning.amsel.ui.components.textInk
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.groupAfter

@Composable
fun ServerScreen(
    state: ServerUiState,
    onBack: () -> Unit,
    onServerClick: (FoundServerUi) -> Unit,
    onTypeAddress: () -> Unit,
    onAddressChange: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val action: (@Composable () -> Unit)? = when (state.step) {
        ServerStep.Searching -> null

        ServerStep.Choose -> {
            {
                SetupButton(
                    stringResource(R.string.setup_type_address),
                    onTypeAddress,
                    filled = false,
                    enabled = !state.connecting,
                )
            }
        }

        ServerStep.Address -> {
            {
                SetupButton(
                    stringResource(R.string.setup_connect),
                    onConnect,
                    enabled = state.canConnect,
                    busy = state.connecting,
                    busyLabel = stringResource(R.string.setup_connecting),
                )
            }
        }
    }
    SetupPage(modifier, title = stringResource(R.string.setup_server_title), onBack = onBack, action = action) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = { fadeIn(Motion.effects()) togetherWith fadeOut(Motion.effects()) },
            label = "server step",
        ) { step ->
            when (step) {
                ServerStep.Searching -> Searching()
                ServerStep.Choose -> ServerList(state.servers, state.connectingTo, onServerClick)
                ServerStep.Address -> AddressForm(state, onAddressChange, onConnect)
            }
        }
    }
}

@Composable
private fun Searching() {
    WaitingState(stringResource(R.string.setup_searching), Modifier.fillMaxSize())
}

@Composable
private fun ServerList(servers: List<FoundServerUi>, connectingTo: String?, onClick: (FoundServerUi) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "choose", contentType = "label") {
            GroupLabel(
                stringResource(R.string.setup_choose),
                above = Space.Part,
                below = headerToRow(RowLead.None, twoLines = true),
            )
        }
        items(servers, key = { it.id }, contentType = { "server" }) { server ->
            ServerRow(
                server,
                connecting = server.id == connectingTo,
                waiting = connectingTo != null && server.id != connectingTo,
                onClick = { onClick(server) },
            )
        }
    }
}

@Composable
private fun ServerRow(server: FoundServerUi, connecting: Boolean, waiting: Boolean, onClick: () -> Unit) {
    val connectingLabel = stringResource(R.string.setup_connecting)
    ListRow(
        title = server.name ?: stringResource(R.string.music_assistant),
        subtitle = server.address,
        modifier = Modifier.semantics { if (connecting) stateDescription = connectingLabel },
        enabled = !waiting,
        onClick = if (connecting) null else onClick,
        trailing = if (connecting) {
            { Spinner(color = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
    )
}

@Composable
private fun AddressForm(state: ServerUiState, onAddressChange: (String) -> Unit, onConnect: () -> Unit) {
    val error = state.error
    val type = MaterialTheme.typography
    val density = LocalDensity.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Keyline.Margin, vertical = Space.Part),
    ) {
        if (state.notFound) {
            val sentence = stringResource(R.string.setup_not_found)
            Text(
                sentence,
                style = type.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .textInk(type.bodyLarge)
                    .inkStart(sentence, type.bodyLarge),
            )
            Spacer(Modifier.height(with(density) { groupAfter(type.bodyLarge) }))
        }
        LabelledField(
            value = state.address,
            onValueChange = onAddressChange,
            label = stringResource(R.string.setup_address),
            readOnly = state.connecting,
            helper = stringResource(R.string.setup_address_example),
            error = error?.let { stringResource(it.message) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = {
                if (state.canConnect) onConnect() else defaultKeyboardAction(ImeAction.Go)
            }),
        )
        Spacer(Modifier.height(with(density) { groupAfter(type.bodyMedium) }))
        val note = stringResource(R.string.setup_https)
        Text(
            note,
            style = type.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.textInk(type.bodyMedium).inkStart(note, type.bodyMedium),
        )
    }
}

private val ServerError.message: Int
    get() = when (this) {
        ServerError.Unreachable -> R.string.setup_error_unreachable
        ServerError.TooOld -> PlaybackR.string.error_too_old
    }

/** A Music Assistant server that answered on the network. */
@Immutable
data class FoundServerUi(
    /** The server's own id (`server_id`), stable whatever address it answers on. */
    val id: String,
    /** Servers before schema 29 send no name; they are called Music Assistant. */
    val name: String?,
    /** For display, host only: `192.168.1.20` or `music.example.com`. Never a port. */
    val address: String,
)

/** The three faces of "Your server". */
enum class ServerStep {
    /**
     * Looking on the network. A single server that answers is picked automatically and connects
     * from here, so this step leads straight to Sign in.
     */
    Searching,

    /** Two or more servers answered: the person taps the one they use. */
    Choose,

    /**
     * One field for the address: nothing answered within five seconds, the person asked to type an
     * address, or a found server could not be reached (its address is then filled in).
     */
    Address,
}

/** Why the typed address did not lead to a server. Shown under the field, in plain words. */
enum class ServerError {
    /** Nothing answered as Music Assistant at that address. */
    Unreachable,

    /** Music Assistant answered, but it is older than the oldest release the app supports (2.10). */
    TooOld,
}

/**
 * Everything "Your server" draws. Ports, TLS and certificates never appear: the logic adds
 * `http://` and port 8095 to what people type and shows addresses without a port, and an
 * `https://` address covers TLS.
 */
@Immutable
data class ServerUiState(
    val step: ServerStep = ServerStep.Searching,
    /** Servers found so far, in the order they answered. */
    val servers: List<FoundServerUi> = emptyList(),
    /** The address field, exactly as typed. */
    val address: String = "",
    /**
     * What is being connected to while the app checks it and reads its sign-in methods: a found
     * server's [FoundServerUi.id] (its row shows progress), or the typed address (the button
     * shows progress). Null when nothing is connecting.
     */
    val connectingTo: String? = null,
    /** Only for the typed address. Cleared when the address changes. */
    val error: ServerError? = null,
    /** The search ended without an answer, so the address step says why it is there. */
    val notFound: Boolean = false,
) {
    val connecting: Boolean get() = connectingTo != null

    /** Connect is offered once something is typed, and not twice. */
    val canConnect: Boolean get() = address.isNotBlank() && !connecting
}
