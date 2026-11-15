package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import kotlin.math.roundToInt

/** BasicTextField's caret, which Compose does not let a field change. */
private val Caret = 2.dp

/**
 * The text of a field and its placeholder, both with their first letter's ink on the field's text
 * keyline. An empty field's caret stands just before that keyline, so it never covers the
 * placeholder's first letter; typed text then moves onto the keyline.
 */
@Composable
private fun FieldText(
    value: String,
    placeholder: String?,
    field: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.bodyLarge
    val bearing = rememberSideBearings(value, style).first
    val density = LocalDensity.current
    val shift = if (value.isEmpty()) with(density) { (Caret + 1.dp).toPx() } else bearing
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty() && placeholder != null) {
            Words(
                placeholder,
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.inkStart(placeholder, style),
            )
        }
        Box(Modifier.offset { IntOffset(-shift.roundToInt(), 0) }) { field() }
    }
}

@Composable
private fun FieldBox(
    error: Boolean,
    fill: Color,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.FieldHeight)
            .clip(Shapes.Tile)
            .background(fill)
            .then(if (error) Modifier.border(Sizes.Hairline, colors.error, Shapes.Tile) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * A 48 dp field in the first surface tone with the tile radius: the glyph 16 inside its edge, the
 * query 12 after it. [busy] turns the glyph into a spinner of the same size while a search is on its way.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    busyLabel: String? = null,
    onSearch: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val glyph = wordGlyph(MaterialTheme.typography.bodyLarge)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().padding(horizontal = Keyline.Margin),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        decorationBox = { field ->
            FieldBox(error = false, MaterialTheme.colorScheme.surfaceContainerLow) {
                Spacer(Modifier.width(Space.Sibling))
                Box(contentAlignment = Alignment.Center) {
                    if (busy) {
                        Spinner(
                            Modifier.semantics { busyLabel?.let { contentDescription = it } },
                            size = glyph,
                            color = colors.onSurfaceVariant,
                        )
                    } else {
                        InkGlyph(R.drawable.ic_search, tint = colors.onSurfaceVariant, size = glyph)
                    }
                }
                Spacer(Modifier.width(Space.S3))
                FieldText(value, placeholder, field, Modifier.weight(1f))
                if (value.isNotEmpty()) {
                    IconAction(
                        R.drawable.ic_close,
                        clearLabel,
                        onClear,
                        Modifier.padding(start = Space.Part, end = Space.Sibling),
                        edge = InkEdge.End,
                        tint = colors.onSurfaceVariant,
                        size = glyph,
                    )
                } else {
                    Spacer(Modifier.width(Space.Sibling))
                }
            }
        },
    )
}

/**
 * The search field at rest, as a button: Home's Search section. It looks as Search's own field does
 * while empty; a tap opens Search, the keyboard up, where that field takes the words.
 */
@Composable
fun SearchEntry(placeholder: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val glyph = wordGlyph(MaterialTheme.typography.bodyLarge)
    Box(modifier.fillMaxWidth().padding(horizontal = Keyline.Margin)) {
        FieldBox(
            error = false,
            colors.surfaceContainerLow,
            Modifier
                .clip(Shapes.Tile)
                .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {},
        ) {
            Spacer(Modifier.width(Space.Sibling))
            InkGlyph(R.drawable.ic_search, tint = colors.onSurfaceVariant, size = glyph)
            Spacer(Modifier.width(Space.S3))
            FieldText("", placeholder, field = {}, Modifier.weight(1f))
            Spacer(Modifier.width(Space.Sibling))
        }
    }
}

/**
 * A labelled field: the label's baseline 8 dp above the field, the text 16 dp inside it, and a
 * [FieldNote] under it. Label, field edge and note share the margin. The label is part of the
 * field, so TalkBack names the field by it and a tap on the label focuses it.
 */
@Composable
fun LabelledField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    helper: String? = null,
    error: String? = null,
    readOnly: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    fieldModifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    /** The field's tone: the first surface tone on a page; a sheet, which is in that tone, takes a higher one. */
    fill: Color = MaterialTheme.colorScheme.surfaceContainerLow,
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Column(modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier
                .fillMaxWidth()
                .then(if (error != null) Modifier.semantics { this.error(error) } else Modifier),
            readOnly = readOnly,
            singleLine = true,
            textStyle = type.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(if (error != null) colors.error else colors.primary),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            decorationBox = { field ->
                // What the field draws: a password's bullets, whose ink sets the keyline, not the secret's.
                val shown = remember(value, visualTransformation) {
                    visualTransformation.filter(AnnotatedString(value)).text.text
                }
                Column {
                    Text(
                        label,
                        style = type.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.textInk(type.bodyMedium).inkStart(label, type.bodyMedium),
                    )
                    Spacer(Modifier.height(Space.Part))
                    FieldBox(error = error != null, fill) {
                        Spacer(Modifier.width(Space.Sibling))
                        FieldText(shown, null, field, Modifier.weight(1f))
                        Spacer(Modifier.width(Space.Sibling))
                        if (trailing != null) {
                            trailing()
                            Spacer(Modifier.width(Space.Sibling))
                        }
                    }
                }
            },
        )
        val line = error ?: helper
        if (line != null) FieldNote(line, error = error != null)
    }
}

/**
 * A helper or error line, set by its capitals [Space.Part] under the edge above it: a field's, or
 * a button's when the error is about that button. TalkBack reads an error as it appears.
 */
@Composable
fun FieldNote(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    val style = MaterialTheme.typography.bodyMedium
    Text(
        text,
        style = style,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .then(if (error) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
            .padding(top = Space.Part)
            .textInk(style)
            .inkStart(text, style),
    )
}
