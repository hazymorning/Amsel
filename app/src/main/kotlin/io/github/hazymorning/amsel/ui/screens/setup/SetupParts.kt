package io.github.hazymorning.amsel.ui.screens.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.PrimaryButton
import io.github.hazymorning.amsel.ui.components.SecondaryButton
import io.github.hazymorning.amsel.ui.components.Spinner
import io.github.hazymorning.amsel.ui.components.trimToGlyphHeight
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space

/**
 * One setup page. Setup sits outside the app shell, so the page keeps itself clear of the system
 * bars and the keyboard: an optional top bar, the content, and the [action] at the bottom, where it
 * rides just above the keyboard.
 */
@Composable
internal fun SetupPage(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (title != null) PageTopBar(title, onBack)
        Column(Modifier.fillMaxWidth().weight(1f)) { content() }
        if (action != null) Box(Modifier.fillMaxWidth().padding(Keyline.Margin)) { action() }
    }
}

/**
 * A full-width, 48 dp setup button. [filled] marks the screen's one primary action; any other is
 * the tonal secondary. While [busy] it shows a spinner in place of its label and ignores taps, keeping its
 * size and colour so nothing jumps; [busyLabel] tells TalkBack what is happening.
 */
@Composable
internal fun SetupButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyLabel: String? = null,
) {
    val buttonModifier = modifier
        .fillMaxWidth()
        .semantics { if (busy && busyLabel != null) contentDescription = busyLabel }
    val click = { if (!busy) onClick() }
    val spinner: (@Composable RowScope.() -> Unit)? = if (busy) {
        { Spinner() }
    } else {
        null
    }
    if (filled) {
        PrimaryButton(text, click, buttonModifier, enabled = enabled || busy, content = spinner)
    } else {
        SecondaryButton(text, click, buttonModifier, enabled = enabled || busy, content = spinner)
    }
}

/**
 * "or" between two ways of doing the same thing: one quiet centred word, a group's space from its
 * neighbours measured from its own ink ("or" has no capitals, "oder" an ascender).
 */
@Composable
internal fun OrLabel(modifier: Modifier = Modifier, above: Dp = Space.Group) {
    val word = stringResource(R.string.setup_or)
    val style = MaterialTheme.typography.bodyMedium
    Text(
        word,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = above, bottom = Space.Group)
            .trimToGlyphHeight(word, style),
    )
}
