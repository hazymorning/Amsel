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
import io.github.hazymorning.amsel.ui.components.capToBaseline
import io.github.hazymorning.amsel.ui.components.headerToRow
import io.github.hazymorning.amsel.ui.components.trimStartBearing
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
                    .capToBaseline(type.bodyLarge)
                    .trimStartBearing(sentence, type.bodyLarge),
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
            modifier = Modifier.capToBaseline(type.bodyMedium).trimStartBearing(note, type.bodyMedium),
        )
    }
}

private val ServerError.message: Int
    get() = when (this) {
        ServerError.Unreachable -> R.string.setup_error_unreachable
        ServerError.TooOld -> PlaybackR.string.error_too_old
        ServerError.Certificate -> R.string.setup_error_certificate
        ServerError.HttpsFailed -> R.string.setup_error_https
    }

@Immutable
data class FoundServerUi(
    /** `server_id`: stable per install, whatever address the server answers on. */
    val id: String,
    /** Null from servers before schema 29, which send no name (docs/API-NOTES.md, "server_info"). */
    val name: String?,
    /** Host only, without scheme or port: for display. */
    val address: String,
)

enum class ServerStep {
    /** A single server that answers connects from here, skipping [Choose]. */
    Searching,

    Choose,

    /**
     * Entered when nothing answers within [SetupViewModel.SEARCH_MS], the user asks to type an address,
     * or a found server cannot be reached (its address is then filled in).
     */
    Address,
}

enum class ServerError {
    Unreachable,

    TooOld,

    Certificate,

    /** A name typed without a scheme failed over https; plain http must be typed explicitly. */
    HttpsFailed,
}

@Immutable
data class ServerUiState(
    val step: ServerStep = ServerStep.Searching,
    val servers: List<FoundServerUi> = emptyList(),
    /** As typed; parsed only on connect. */
    val address: String = "",
    /** A found server's [FoundServerUi.id] or the typed address, while it is checked and its sign-in methods read. */
    val connectingTo: String? = null,
    /** About the typed address; cleared when it changes. */
    val error: ServerError? = null,
    val notFound: Boolean = false,
) {
    val connecting: Boolean get() = connectingTo != null

    val canConnect: Boolean get() = address.isNotBlank() && !connecting
}
