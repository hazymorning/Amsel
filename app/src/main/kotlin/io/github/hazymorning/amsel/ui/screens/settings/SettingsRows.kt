package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.ChoiceRow
import io.github.hazymorning.amsel.ui.components.InkGlyph
import io.github.hazymorning.amsel.ui.components.ListRow
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.TextAction
import io.github.hazymorning.amsel.ui.components.textInkWidth
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.firm

/** A setting's row in its block ([SettingsGroup]): the second surface, its place's corners, its words inset. */
@Composable
internal fun SettingRow(
    title: String,
    shape: Shape,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    role: Role = Role.Button,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    ListRow(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        band = MaterialTheme.colorScheme.surfaceContainerLow,
        bandShape = shape,
        inset = RowInset,
        edge = BlockEdge,
        titleLines = WHOLE,
        subtitleLines = WHOLE,
        role = role,
        onClick = onClick,
        trailing = trailing,
    )
}

/**
 * Whether a row's [button] stands beside its [name]: while every word of the name fits the room
 * the button leaves. Else the button gets a row of its own, so large type or a long account never
 * breaks a word to keep it there.
 */
@Composable
internal fun buttonFitsBeside(name: String, button: String): Boolean {
    val words = MaterialTheme.typography.bodyLarge
    val row = LocalWindowInfo.current.containerDpSize.width - (BlockEdge + RowInset) * 2
    val room = row - Space.Sibling - textInkWidth(button, words.firm())
    return name.split(' ').all { textInkWidth(it, words) <= room }
}

/**
 * The account's rows: who is signed in and where ([server]'s name and address), with Sign out
 * beside it, or on a row of its own under it where [signOutBeside] is false ([buttonFitsBeside]).
 */
internal fun SettingsRows.accountRows(
    signedIn: String,
    server: List<String>,
    signOut: String,
    signOutBeside: Boolean,
    onSignOut: () -> Unit,
) {
    val where = server.filter { it.isNotBlank() }.joinToString(DOT_SEPARATOR)
    row { shape ->
        SettingRow(
            signedIn,
            shape,
            subtitle = where,
            trailing = if (signOutBeside) {
                { TextAction(signOut, onSignOut) }
            } else {
                null
            },
        )
    }
    if (!signOutBeside) row { shape -> SettingRow(signOut, shape, onClick = onSignOut) }
}

/** A setting with a few values: its name, and under it the value it has, which a tap changes. */
@Composable
internal fun ValueRow(title: String, value: String, shape: Shape, onClick: () -> Unit) {
    SettingRow(title, shape, subtitle = value, role = Role.DropdownList, onClick = onClick)
}

/** A setting's values, the current one marked; a tap chooses and closes. */
@Composable
internal fun <T> ChoiceSheet(
    title: String,
    values: List<T>,
    current: T,
    label: @Composable (T) -> String,
    onPick: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().selectableGroup().padding(bottom = Space.Group)) {
        SheetHeader(title)
        Spacer(Modifier.height(Space.HeaderToContent))
        values.forEach { value -> ChoiceRow(label(value), selected = value == current, onClick = { onPick(value) }) }
    }
}

/** A setting that is on or off, in its block: the whole row toggles it; the switch is ink when on. */
@Composable
internal fun ToggleRow(title: String, detail: String, checked: Boolean, shape: Shape, onChange: (Boolean) -> Unit) {
    SettingRow(
        title,
        shape,
        modifier = Modifier.semantics { toggleableState = ToggleableState(checked) },
        subtitle = detail,
        role = Role.Switch,
        onClick = { onChange(!checked) },
        trailing = { SettingSwitch(checked) },
    )
}

/** The same setting on a sheet, which is a surface of its own and needs no block. */
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

/** The switch shows the state; the row it sits in takes the tap and says it to TalkBack. */
@Composable
private fun SettingSwitch(checked: Boolean) {
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
    InkGlyph(R.drawable.ic_open_external, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A setting's name and its description are read whole at any type size: they wrap, never cut. */
private const val WHOLE = Int.MAX_VALUE
