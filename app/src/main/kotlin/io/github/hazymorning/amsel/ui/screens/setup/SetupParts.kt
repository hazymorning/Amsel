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

/** Setup is outside the app shell, so the page pads itself for the system bars and the keyboard. */
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

/** While [busy] it ignores taps but stays enabled, so its colours do not change. */
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

/** Spaced from the word's glyph bounds ([trimToGlyphHeight]): "or" has no capitals, "oder" has an ascender. */
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
