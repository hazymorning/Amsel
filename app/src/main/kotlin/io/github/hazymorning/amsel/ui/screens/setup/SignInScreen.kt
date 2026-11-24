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
import io.github.hazymorning.amsel.ui.components.capToBaseline
import io.github.hazymorning.amsel.ui.components.trimStartBearing
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
            modifier = Modifier.capToBaseline(type.bodyLarge).trimStartBearing(name, type.bodyLarge),
        )
        Words(
            address,
            style = type.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .paddingFromBaseline(top = with(LocalDensity.current) { secondLine(type.bodyMedium) })
                .capToBaseline(type.bodyMedium, top = false)
                .trimStartBearing(address, type.bodyMedium),
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

/** Words rather than an eye icon, so the toggle explains itself. */
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

enum class SignInMethod { HomeAssistant, Password }

enum class SignInError {
    /** The server refused the username or the password. */
    WrongPassword,

    TooManyAttempts,

    AccountDisabled,

    /** Any other failure: the server stopped answering, or the token could not be kept ([attempt]). */
    Unreachable,

    /** It could not start, the page returned without a code, or completing the sign-in failed. */
    HomeAssistant,
}

@Immutable
data class SignInUiState(
    val serverName: String,
    /** Host only, without scheme or port: for display. */
    val serverAddress: String,
    val offersHomeAssistant: Boolean = false,
    val username: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val busy: SignInMethod? = null,
    val error: SignInError? = null,
) {
    val canSignIn: Boolean get() = busy == null && username.isNotBlank() && password.isNotEmpty()

    val editable: Boolean get() = busy == null
}
