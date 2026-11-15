package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.UpdateState
import io.github.hazymorning.amsel.ui.components.PrimaryButton
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.inkStart
import io.github.hazymorning.amsel.ui.components.textInk
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space

/**
 * Updates, in their own sheet: where Amsel stands, read whole at any type size, the one button that
 * moves it on (look, or download what was found), and whether to look at each start.
 */
@Composable
internal fun UpdatesSheetContent(
    state: UpdateState,
    version: String,
    lookAtStart: Boolean,
    onLookAtStart: (Boolean) -> Unit,
    onLook: () -> Unit,
    onDownload: (UpdateState.Available) -> Unit,
) {
    val style = MaterialTheme.typography.bodyLarge
    val sentence = when (state) {
        UpdateState.Unknown -> stringResource(R.string.updates_current, version)
        UpdateState.Looking -> stringResource(R.string.updates_looking)
        UpdateState.Newest -> stringResource(R.string.updates_newest, version)
        is UpdateState.Available -> stringResource(R.string.updates_available, state.version)
        UpdateState.Failed -> stringResource(R.string.updates_failed)
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Space.Group)) {
        SheetHeader(stringResource(R.string.settings_updates))
        Spacer(Modifier.height(Space.HeaderToContent))
        Words(
            sentence,
            style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = Int.MAX_VALUE,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Keyline.Margin)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .textInk(style)
                .inkStart(sentence, style),
        )
        Spacer(Modifier.height(Space.Group))
        PrimaryButton(
            stringResource(if (state is UpdateState.Available) R.string.updates_download else R.string.updates_look),
            onClick = { if (state is UpdateState.Available) onDownload(state) else onLook() },
            enabled = state != UpdateState.Looking,
            modifier = Modifier.padding(horizontal = Keyline.Margin).fillMaxWidth(),
        )
        Spacer(Modifier.height(Space.Group))
        PlainToggleRow(
            stringResource(R.string.updates_at_start),
            stringResource(R.string.updates_at_start_detail),
            lookAtStart,
            onLookAtStart,
        )
    }
}
