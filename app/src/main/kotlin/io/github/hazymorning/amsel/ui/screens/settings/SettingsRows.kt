package io.github.hazymorning.amsel.ui.screens.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.AlignedGlyph
import io.github.hazymorning.amsel.ui.components.ChoiceRow
import io.github.hazymorning.amsel.ui.components.ListRow
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.TextAction
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.capToBaseline
import io.github.hazymorning.amsel.ui.components.headerToRow
import io.github.hazymorning.amsel.ui.components.sheetEnd
import io.github.hazymorning.amsel.ui.components.textGlyphWidth
import io.github.hazymorning.amsel.ui.components.trimStartBearing
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.model.ServerStatus
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.firm

/** [mark] stands before the second line, such as the dot of an update ready. */
@Composable
internal fun SettingRow(
    title: String,
    shape: Shape,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    role: Role = Role.Button,
    onClick: (() -> Unit)? = null,
    @DrawableRes glyph: Int? = null,
    mark: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    ListRow(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        lead = if (glyph != null) RowLead.Glyph else RowLead.None,
        band = MaterialTheme.colorScheme.surfaceContainerLow,
        bandShape = shape,
        inset = RowInset,
        edge = BlockEdge,
        titleLines = WHOLE,
        subtitleLines = WHOLE,
        role = role,
        onClick = onClick,
        leading = glyph?.let { { AlignedGlyph(it, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailing = trailing,
        mark = mark,
    )
}

/**
 * True while every word of [name] fits beside [button]; otherwise the button gets a row of its own, so
 * large type or a long account name never breaks a word.
 */
@Composable
internal fun buttonFitsBeside(name: String, button: String, glyph: Boolean = false): Boolean {
    val words = MaterialTheme.typography.bodyLarge
    val row = LocalWindowInfo.current.containerDpSize.width - (BlockEdge + RowInset) * 2 -
        if (glyph) Sizes.Lead + Space.Sibling else 0.dp
    val room = row - Space.Sibling - textGlyphWidth(button, words.firm())
    return name.split(' ').all { textGlyphWidth(it, words) <= room }
}

internal fun SettingsRows.accountRows(
    signedIn: String,
    server: List<String>,
    signOut: String,
    signOutBeside: Boolean,
    onSignOut: () -> Unit,
) {
    val where = server.filter { it.isNotBlank() }.dotted()
    row { shape ->
        SettingRow(
            signedIn,
            shape,
            subtitle = where,
            glyph = R.drawable.ic_artist,
            trailing = if (signOutBeside) {
                { TextAction(signOut, onSignOut) }
            } else {
                null
            },
        )
    }
    if (!signOutBeside) row { shape -> SettingRow(signOut, shape, onClick = onSignOut, glyph = R.drawable.ic_sign_out) }
}

@Composable
internal fun ValueRow(title: String, value: String, shape: Shape, onClick: () -> Unit) {
    SettingRow(title, shape, subtitle = value, role = Role.DropdownList, onClick = onClick)
}

@Composable
internal fun <T> ChoiceSheet(
    title: String,
    values: List<T>,
    current: T,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
    detail: String? = null,
) {
    Column(Modifier.fillMaxWidth().selectableGroup().padding(bottom = sheetEnd(RowLead.None, twoLines = false))) {
        SheetHeader(title)
        Spacer(Modifier.height(if (detail != null) Space.HeaderToContent else Space.HeaderToRows))
        if (detail != null) {
            val style = MaterialTheme.typography.bodyMedium
            Words(
                detail,
                style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = Int.MAX_VALUE,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Keyline.Margin)
                    .capToBaseline(style)
                    .trimStartBearing(detail, style),
            )
            Spacer(Modifier.height(headerToRow(RowLead.None, twoLines = false)))
        }
        values.forEach { value -> ChoiceRow(label(value), selected = value == current, onClick = { onPick(value) }) }
    }
}

@Composable
internal fun ToggleRow(title: String, detail: String, checked: Boolean, shape: Shape, onChange: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    SettingRow(
        title,
        shape,
        modifier = Modifier.semantics { toggleableState = ToggleableState(checked) },
        subtitle = detail,
        role = Role.Switch,
        onClick = {
            haptics.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onChange(!checked)
        },
        trailing = { SettingSwitch(checked) },
    )
}

/** For a sheet, which is a surface of its own and needs no block. */
@Composable
internal fun PlainToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListRow(
        title = title,
        modifier = Modifier.semantics { toggleableState = ToggleableState(checked) },
        subtitle = detail,
        titleLines = WHOLE,
        subtitleLines = WHOLE,
        role = Role.Switch,
        onClick = { onChange(!checked) },
        trailing = { SettingSwitch(checked) },
    )
}

/** Display only: the row around it takes the tap and carries the semantics. */
@Composable
internal fun SettingSwitch(checked: Boolean) {
    val colors = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = null,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.surface,
            checkedTrackColor = colors.onSurface,
            checkedBorderColor = colors.onSurface,
            uncheckedThumbColor = colors.outline,
            uncheckedTrackColor = colors.surfaceContainerHigh,
            uncheckedBorderColor = colors.surfaceContainerHigh,
        ),
        modifier = Modifier.clearAndSetSemantics {},
    )
}

@Composable
internal fun ExternalGlyph() {
    AlignedGlyph(R.drawable.ic_open_external, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val WHOLE = Int.MAX_VALUE

@Composable
internal fun connectionLine(connection: ServerStatus): String = stringResource(
    when (connection) {
        ServerStatus.Connected -> R.string.settings_connected
        ServerStatus.Connecting -> R.string.settings_connecting
        ServerStatus.Offline -> R.string.settings_unreachable
    },
)

@Composable
internal fun sleepLine(state: SettingsUiState): String = listOfNotNull(
    if (state.sleepFadeSec > 0) {
        stringResource(
            R.string.settings_sleep_fade_value,
            stringResource(R.string.settings_sleep_seconds, state.sleepFadeSec),
        )
    } else {
        stringResource(R.string.settings_sleep_no_fade)
    },
    stringResource(R.string.settings_sleep_shake).takeIf { state.sleepShake },
    stringResource(R.string.settings_sleep_on_play).takeIf { state.sleepOnPlay },
).dotted()

/** A no-break space keeps each dot with the part before it, so no wrapped line starts with one. */
internal fun List<String>.dotted(): String = joinToString("\u00A0" + DOT_SEPARATOR.trimStart())

@Composable
internal fun MossDot() {
    Box(Modifier.size(MossDotSize).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
}

private val MossDotSize = 8.dp
