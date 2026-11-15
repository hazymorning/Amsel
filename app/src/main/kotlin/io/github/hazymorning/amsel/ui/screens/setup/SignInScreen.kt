package io.github.hazymorning.amsel.ui.screens.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.FieldNote
import io.github.hazymorning.amsel.ui.components.LabelledField
import io.github.hazymorning.amsel.ui.components.TextAction
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.inkStart
import io.github.hazymorning.amsel.ui.components.textInk
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.groupAfter
import io.github.hazymorning.amsel.ui.theme.secondLine

@Composable
fun SignInScreen(
    state: SignInUiState,
    onBack: () -> Unit,
    onSignInWithHomeAssistant: () -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordVisibleChange: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val signingIn = stringResource(R.string.setup_signing_in)
    SetupPage(
        modifier,
        title = stringResource(R.string.setup_sign_in),
        onBack = onBack,
        action = {
            SetupButton(
                stringResource(R.string.setup_sign_in),
                onSignIn,
                enabled = state.canSignIn,
                busy = state.busy == SignInMethod.Password,
                busyLabel = signingIn,
            )
        },
    ) {
        val afterLines = with(LocalDensity.current) { groupAfter(MaterialTheme.typography.bodyMedium) }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Keyline.Margin),
        ) {
            ServerName(state.serverName, state.serverAddress)
            Spacer(Modifier.height(afterLines))
            val error = state.error
            val haError = error == SignInError.HomeAssistant && state.offersHomeAssistant
            if (state.offersHomeAssistant) {
                SetupButton(
                    stringResource(R.string.setup_sign_in_home_assistant),
                    onSignInWithHomeAssistant,
                    filled = false,
                    enabled = state.busy == null,
                    busy = state.busy == SignInMethod.HomeAssistant,
                    busyLabel = signingIn,
                )
                if (haError) FieldNote(stringResource(R.string.setup_error_home_assistant), error = true)
                OrLabel(above = if (haError) afterLines else Space.Group)
            }
            UsernameField(state, onUsernameChange)
            Spacer(Modifier.height(Space.Group))
            PasswordField(state, onPasswordChange, onPasswordVisibleChange, onSignIn)
            if (error != null && error != SignInError.WrongPassword && !haError) {
                FieldNote(stringResource(error.message), error = true)
            }
        }
    }
}

@Composable
private fun ServerName(name: String, address: String) {
    val type = MaterialTheme.typography
    Column(Modifier.padding(top = Space.Part).semantics(mergeDescendants = true) {}) {
        Words(
            name,
            style = type.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.textInk(type.bodyLarge).inkStart(name, type.bodyLarge),
        )
        Words(
            address,
            style = type.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .paddingFromBaseline(top = with(LocalDensity.current) { secondLine(type.bodyMedium) })
                .textInk(type.bodyMedium, top = false)
                .inkStart(address, type.bodyMedium),
        )
    }
}

@Composable
private fun UsernameField(state: SignInUiState, onUsernameChange: (String) -> Unit) {
    LabelledField(
        value = state.username,
        onValueChange = onUsernameChange,
        label = stringResource(R.string.setup_username),
        readOnly = !state.editable,
        fieldModifier = Modifier.semantics { contentType = ContentType.Username },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next,
        ),
    )
}

@Composable
private fun PasswordField(
    state: SignInUiState,
    onPasswordChange: (String) -> Unit,
    onPasswordVisibleChange: (Boolean) -> Unit,
    onSignIn: () -> Unit,
) {
    LabelledField(
        value = state.password,
        onValueChange = onPasswordChange,
        label = stringResource(R.string.setup_password),
        readOnly = !state.editable,
        error = if (state.error == SignInError.WrongPassword) stringResource(R.string.setup_error_password) else null,
        fieldModifier = Modifier.semantics { contentType = ContentType.Password },
        visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = {
            if (state.canSignIn) onSignIn() else defaultKeyboardAction(ImeAction.Done)
        }),
        trailing = { ShowHideButton(state.passwordVisible, onPasswordVisibleChange) },
    )
}

/** "Show" or "Hide" in words rather than an eye, so the toggle explains itself. */
@Composable
private fun ShowHideButton(visible: Boolean, onChange: (Boolean) -> Unit) {
    TextAction(
        stringResource(if (visible) R.string.setup_hide else R.string.setup_show),
        onClick = { onChange(!visible) },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        contentDescription = stringResource(
            if (visible) R.string.setup_hide_password else R.string.setup_show_password,
        ),
    )
}

private val SignInError.message: Int
    get() = when (this) {
        SignInError.WrongPassword -> R.string.setup_error_password
        SignInError.TooManyAttempts -> R.string.setup_error_too_many_attempts
        SignInError.AccountDisabled -> R.string.setup_error_account_disabled
        SignInError.Unreachable -> R.string.setup_error_sign_in_unreachable
        SignInError.HomeAssistant -> R.string.setup_error_home_assistant
    }

/** The two ways in: Home Assistant (when the server offers it) and username and password. */
enum class SignInMethod { HomeAssistant, Password }

/** What went wrong, said in plain sentences on screen; never a code. */
enum class SignInError {
    /** "That password didn't work": the server refused the username or password. */
    WrongPassword,

    /** The server refuses the account for a while after failed attempts. */
    TooManyAttempts,

    AccountDisabled,

    /** The server stopped answering while signing in. */
    Unreachable,

    /** Home Assistant's sign-in could not start, its page came back without a code, or the server refused the code. */
    HomeAssistant,
}

/**
 * Everything Sign in draws. The server was checked on the way here, so its name, address and
 * sign-in methods are always known. Tokens never appear: the app keeps a long-lived one itself.
 */
@Immutable
data class SignInUiState(
    val serverName: String,
    /** For display, host only: `192.168.1.20` or `music.example.com`. Never a port. */
    val serverAddress: String,
    /** The server lists Home Assistant among its sign-in methods. */
    val offersHomeAssistant: Boolean = false,
    val username: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    /** The sign-in that is running, so its button shows progress; null when idle. */
    val busy: SignInMethod? = null,
    /** Cleared when the person edits a field or starts again. */
    val error: SignInError? = null,
) {
    /** Sign in is offered once both fields are filled, and not twice. */
    val canSignIn: Boolean get() = busy == null && username.isNotBlank() && password.isNotEmpty()

    /** Fields stay put while a sign-in runs. */
    val editable: Boolean get() = busy == null
}
