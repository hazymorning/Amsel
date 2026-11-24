package io.github.hazymorning.amsel.ui.screens.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.data.downloads.AbsAccount
import io.github.hazymorning.amsel.data.downloads.AbsException
import io.github.hazymorning.amsel.data.lastAbsAccount
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.components.FieldNote
import io.github.hazymorning.amsel.ui.components.LabelledField
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.SheetQuestion
import io.github.hazymorning.amsel.ui.components.TextAction
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.capToBaseline
import io.github.hazymorning.amsel.ui.components.trimStartBearing
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.rememberLocalNetworkGate
import io.github.hazymorning.amsel.ui.screens.setup.SetupButton
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space
import kotlinx.coroutines.launch

@Composable
internal fun AbsIntro(text: String = stringResource(R.string.abs_intro)) {
    val style = MaterialTheme.typography.bodyLarge
    Words(
        text,
        style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // The sheet scrolls, so the text shows whole at any font scale.
        maxLines = Int.MAX_VALUE,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Keyline.Margin)
            .capToBaseline(style)
            .trimStartBearing(text, style),
    )
}

/** Returns null on success, else why it failed. */
typealias AbsSignInAction = suspend (address: String, username: String, password: String) -> AbsException.Reason?

@Composable
internal fun AbsSignInForm(
    button: String,
    signIn: AbsSignInAction,
    onSignedIn: () -> Unit,
    modifier: Modifier = Modifier,
    last: AbsAccount? = null,
) {
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(last?.url.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(last?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<AbsException.Reason?>(null) }
    val ready = address.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
    // Always shown in a sheet, whose background is the fields' default fill.
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    // Android 17 needs the local network permission before a home address can be reached.
    val send = rememberLocalNetworkGate {
        // The button is disabled while busy, but the keyboard's Done can still fire.
        if (!busy) {
            busy = true
            error = null
            scope.launch {
                val failed = signIn(address, username, password)
                busy = false
                if (failed == null) onSignedIn() else error = failed
            }
        }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = Keyline.Margin)) {
        LabelledField(
            value = address,
            onValueChange = {
                address = it
                error = null
            },
            label = stringResource(R.string.abs_address),
            helper = stringResource(R.string.abs_address_example),
            error = error?.addressError?.let { stringResource(it) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            fill = fill,
        )
        Spacer(Modifier.height(Space.Group))
        LabelledField(
            value = username,
            onValueChange = {
                username = it
                error = null
            },
            label = stringResource(R.string.setup_username),
            fieldModifier = Modifier.semantics { contentType = ContentType.Username },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            fill = fill,
        )
        Spacer(Modifier.height(Space.Group))
        LabelledField(
            value = password,
            onValueChange = {
                password = it
                error = null
            },
            label = stringResource(R.string.setup_password),
            error = if (error == AbsException.Reason.WrongLogin) stringResource(R.string.abs_error_login) else null,
            fieldModifier = Modifier.semantics { contentType = ContentType.Password },
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (ready) send() }),
            trailing = {
                TextAction(
                    stringResource(if (visible) R.string.setup_hide else R.string.setup_show),
                    onClick = { visible = !visible },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = stringResource(
                        if (visible) R.string.setup_hide_password else R.string.setup_show_password,
                    ),
                )
            },
            fill = fill,
        )
        val other = error?.takeIf { it.addressError == null && it != AbsException.Reason.WrongLogin }
        if (other != null) FieldNote(stringResource(R.string.abs_error_server), error = true)
        Spacer(Modifier.height(Space.Group))
        SetupButton(button, send, enabled = ready, busy = busy, busyLabel = stringResource(R.string.setup_signing_in))
    }
}

private val AbsException.Reason.addressError: Int?
    get() = when (this) {
        AbsException.Reason.BadAddress -> R.string.abs_error_address
        AbsException.Reason.Unreachable -> R.string.abs_error_unreachable
        AbsException.Reason.Certificate -> R.string.abs_error_certificate
        AbsException.Reason.HttpsFailed -> R.string.abs_error_https
        AbsException.Reason.AwayFromHome -> R.string.abs_error_away
        else -> null
    }

@Composable
fun AbsSignInRoute(then: MediaUi, onDone: () -> Unit) {
    val graph = LocalContext.current.graph
    val actions = LocalAppActions.current
    val prefs by graph.data.prefs.prefs.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Space.SheetEdge)) {
        SheetHeader(stringResource(R.string.download))
        Spacer(Modifier.height(Space.HeaderToContent))
        AbsIntro()
        Spacer(Modifier.height(Space.Group))
        AbsSignInForm(
            stringResource(R.string.abs_sign_in_download),
            graph.downloading::signIn,
            onSignedIn = {
                actions.download(then)
                onDone()
            },
            last = prefs?.lastAbsAccount(),
        )
    }
}

@Composable
fun RemoveDownloadRoute(item: MediaUi, onClose: () -> Unit) {
    val graph = LocalContext.current.graph
    SheetQuestion(
        title = stringResource(R.string.download_remove),
        question = stringResource(R.string.download_remove_question, item.title),
        primary = stringResource(R.string.remove),
        onPrimary = {
            graph.downloading.remove(item.key)
            onClose()
        },
        secondary = stringResource(R.string.player_confirm_cancel),
        onSecondary = onClose,
    )
}
