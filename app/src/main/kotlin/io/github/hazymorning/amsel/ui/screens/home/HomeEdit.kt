package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.ui.components.DropdownChip
import io.github.hazymorning.amsel.ui.components.MoreButton
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.capToBaseline
import io.github.hazymorning.amsel.ui.components.overhang
import io.github.hazymorning.amsel.ui.components.textGlyphWidth
import io.github.hazymorning.amsel.ui.components.trimSideBearings
import io.github.hazymorning.amsel.ui.components.trimStartBearing
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.capHeight
import kotlin.math.roundToInt

/**
 * A section's title line in edit mode: the title from its capitals to its last baseline, and ⋯ at
 * the line's end, its ink on the margin, centred on the first line's capitals. The ⋯ takes no room
 * of its own above or below, so the sentence follows the title, not the button.
 */
@Composable
internal fun SectionTitleLine(
    title: String,
    titled: Boolean,
    renaming: Boolean,
    onRenaming: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    moves: List<CustomAccessibilityAction>,
    onMore: () -> Unit,
) {
    val cap = with(LocalDensity.current) { capHeight(MaterialTheme.typography.titleLarge) }
    Row(Modifier.fillMaxWidth().padding(horizontal = Keyline.Margin)) {
        SectionTitleField(title, titled, renaming, onRenaming, onRename, moves, Modifier.weight(1f))
        Spacer(Modifier.width(Space.Sibling))
        Box(Modifier.height(cap), contentAlignment = Alignment.Center) {
            MoreButton(title, onMore, Modifier.overhang(top = Sizes.TouchTarget / 2, bottom = Sizes.TouchTarget / 2))
        }
    }
}

/**
 * A section's title in edit mode, in its heading style, on a second line before it is cut. A tap
 * makes it a field to type a new name in ([renaming]), which takes the same lines in the same
 * place, so the tap moves nothing. The name is kept when the field loses focus or Done is pressed,
 * and when edit mode ends while typing. [moves] are TalkBack's way to move the section, which a
 * held press does by touch.
 */
@Composable
private fun SectionTitleField(
    title: String,
    titled: Boolean,
    renaming: Boolean,
    onRenaming: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    moves: List<CustomAccessibilityAction>,
    modifier: Modifier = Modifier,
) {
    // A heading Home does not show stands faint here, where it can still be named.
    val ink = MaterialTheme.colorScheme.run { if (titled) onSurface else outline }
    val style = MaterialTheme.typography.titleLarge.copy(color = ink)
    if (!renaming) {
        val reach = with(LocalDensity.current) { ((Sizes.TouchTarget - capHeight(style)) / 2).coerceAtLeast(0.dp) }
        Words(
            title,
            style,
            color = ink,
            maxLines = TITLE_LINES,
            modifier = modifier
                .overhang(top = reach, bottom = reach)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClickLabel = stringResource(R.string.home_edit_rename_action),
                    role = Role.Button,
                ) { onRenaming(true) }
                .semantics {
                    heading()
                    customActions = moves
                }
                .padding(vertical = reach)
                .capToBaseline(style)
                .trimStartBearing(title, style),
        )
        return
    }
    var text by remember(title) { mutableStateOf(TextFieldValue(title, TextRange(title.length))) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val field = remember { FocusRequester() }
    val typed by rememberUpdatedState(text.text)
    val named by rememberUpdatedState(title)
    val rename by rememberUpdatedState(onRename)
    // Losing focus ends the field and then disposes it: the name is sent once, not for each.
    val sent = remember { arrayOfNulls<String>(1) }
    val commit = { name: String ->
        if (name.trim() != named && name != sent[0]) {
            sent[0] = name
            rename(name)
        }
    }
    // Leaving edit mode while typing keeps the name too.
    DisposableEffect(Unit) { onDispose { commit(typed) } }
    LaunchedEffect(Unit) { field.requestFocus() }
    val label = stringResource(R.string.home_edit_rename)
    BasicTextField(
        text,
        // The field wraps as the title does, but a name is one line of words: a line break is left
        // out and ends the name, so Enter on a keyboard does what Done does.
        onValueChange = {
            val words = it.text.filterNot { c -> c == '\n' || c == '\r' }
            text = it.copy(text = words)
            if (words.length < it.text.length) {
                commit(words)
                focus.clearFocus()
            }
        },
        textStyle = style,
        maxLines = TITLE_LINES,
        cursorBrush = SolidColor(ink),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = modifier
            .focusRequester(field)
            .semantics { contentDescription = label }
            .onFocusChanged {
                if (focused && !it.isFocused) {
                    commit(typed)
                    onRenaming(false)
                }
                focused = it.isFocused
            }
            .fieldCapToBaseline(style)
            .trimStartBearing(text.text, style),
    )
}

/** A name is whole where it is given: a section's title takes a second line in edit mode before it is cut. */
private const val TITLE_LINES = 2

/**
 * [capToBaseline] for the rename field. A name longer than the field's lines scrolls in it and takes its
 * baselines along, so the field is cut by what a line of [style] holds over its capitals and under
 * its baseline: its lines then stand where the title's stood, whatever it shows of the name.
 */
@Composable
private fun Modifier.fieldCapToBaseline(style: TextStyle): Modifier {
    val measurer = rememberTextMeasurer()
    return layout { measurable, constraints ->
        val line = measurer.measure("", style)
        val baseline = line.firstBaseline.roundToInt()
        val over = baseline - capHeight(style).roundToPx()
        val under = line.size.height - baseline
        val field = measurable.measure(constraints)
        layout(field.width, (field.height - over - under).coerceAtLeast(0)) { field.place(0, -over) }
    }
}

/**
 * A section's sentence in edit mode on two lines alike, "Show [what ▾]" over "as [how ▾]": each a
 * word in one column and its chip, so the chips share an edge and changing either never moves what
 * follows.
 */
@Composable
internal fun SectionSentence(section: SectionUi, onWhat: () -> Unit, onHow: () -> Unit) {
    val sentence = Sentence.of(stringResource(R.string.home_edit_sentence))
    val what = stringResource(section.option.inSentence)
    val how = stringResource(section.layout.inSentence)
    // TalkBack names the choice as its sheet does: "What to show: Pinned".
    val whatName = stringResource(section.option.choice)
    val howName = stringResource(section.layout.label)
    val style = MaterialTheme.typography.bodyLarge
    val lead = max(textGlyphWidth(sentence.before, style), textGlyphWidth(sentence.between, style))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Keyline.Margin),
        verticalArrangement = Arrangement.spacedBy(Space.S3),
    ) {
        SentenceLine(sentence.before, lead) {
            DropdownChip(what, onWhat, it, contentDescription = stringResource(R.string.home_edit_what_is, whatName))
        }
        // Search is a field and nothing else.
        if (section.option != SectionOption.Search) {
            SentenceLine(sentence.between, lead) {
                DropdownChip(how, onHow, it, contentDescription = stringResource(R.string.home_edit_how_is, howName))
            }
        }
    }
}

/** One line of the sentence: its word in a column [lead] wide, then its chip, on the chip's first baseline. */
@Composable
private fun SentenceLine(word: String, lead: Dp, chip: @Composable (Modifier) -> Unit) {
    val style = MaterialTheme.typography.bodyLarge
    Row(horizontalArrangement = Arrangement.spacedBy(Space.Part)) {
        Box(Modifier.width(lead).alignByBaseline()) {
            Text(
                word,
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                // By its ink, so the chip alone sets the line's height at any font size.
                modifier = Modifier.capToBaseline(style).trimSideBearings(word, style),
            )
        }
        chip(Modifier.weight(1f, fill = false).alignByBaseline())
    }
}
