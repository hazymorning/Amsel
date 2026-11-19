package io.github.hazymorning.amsel.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.ChoiceRow
import io.github.hazymorning.amsel.ui.components.InkGlyph
import io.github.hazymorning.amsel.ui.components.ListRow
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.sheetEnd
import io.github.hazymorning.amsel.ui.theme.Space

/** Which of the widget page's choices is open on a sheet. */
sealed interface WidgetSheet : java.io.Serializable {
    data object Speaker : WidgetSheet

    data object Style : WidgetSheet

    data object Cells : WidgetSheet

    /** What button [slot] does; one past the last adds one. */
    data class Button(val slot: Int) : WidgetSheet
}

/** The names of the buttons, from the start. */
private val SlotNames = listOf(
    R.string.widget_slot_1,
    R.string.widget_slot_2,
    R.string.widget_slot_3,
    R.string.widget_slot_4,
    R.string.widget_slot_5,
    R.string.widget_slot_6,
    R.string.widget_slot_7,
    R.string.widget_slot_8,
)

/** What button [slot] does, the [current] one ticked (none for a button to add); Play … goes on to pick its item. */
@Composable
internal fun ButtonChoices(slot: Int, current: WidgetSlot?, volumeLimit: Int, onChoose: (WidgetButton) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = sheetEnd(RowLead.Glyph, twoLines = true)),
    ) {
        SheetHeader(stringResource(SlotNames[slot.coerceIn(SlotNames.indices)]))
        Spacer(Modifier.height(Space.HeaderToRows))
        Column(Modifier.selectableGroup()) {
            WidgetButton.entries.forEach { button ->
                ListRow(
                    title = stringResource(button.label),
                    subtitle = note(button, current, volumeLimit),
                    lead = RowLead.Glyph,
                    leading = { InkGlyph(button.glyph, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    selected = current?.button == button,
                    role = Role.RadioButton,
                    titleLines = 2,
                    subtitleLines = 2,
                    onClick = { onChoose(button) },
                )
            }
        }
    }
}

/** Where the widget plays and what it shows: where Amsel plays, or one speaker, whichever Amsel is on. */
@Composable
internal fun SpeakerChoices(current: String?, speakers: List<WidgetSpeaker>, onChoose: (String?) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = sheetEnd(RowLead.None, twoLines = false)),
    ) {
        SheetHeader(stringResource(R.string.widget_speaker))
        Spacer(Modifier.height(Space.HeaderToRows))
        Column(Modifier.selectableGroup()) {
            ChoiceRow(stringResource(R.string.widget_speaker_amsel), selected = current == null, onClick = {
                onChoose(null)
            })
            speakers.forEach { speaker ->
                ChoiceRow(speaker.name, selected = current == speaker.id, onClick = { onChoose(speaker.id) })
            }
        }
    }
}

/** The widget's ground: the blurred cover of what plays, the phone's colours, or the wallpaper. */
@Composable
internal fun StyleChoices(current: WidgetStyle, onChoose: (WidgetStyle) -> Unit) = DescribedChoices(
    stringResource(R.string.widget_style),
    WidgetStyle.entries,
    current,
    label = { stringResource(it.label) },
    note = {
        stringResource(
            when (it) {
                WidgetStyle.Cover -> R.string.widget_look_cover_note
                WidgetStyle.Plain -> R.string.widget_look_plain_note
                WidgetStyle.Clear -> R.string.widget_look_clear_note
            },
        )
    },
    onChoose = onChoose,
)

/** Which buttons stand on a shaded cell: play and pause alone, every one, or none, which fits more. */
@Composable
internal fun CellsChoices(current: WidgetCells, onChoose: (WidgetCells) -> Unit) = DescribedChoices(
    stringResource(R.string.widget_cells),
    WidgetCells.entries,
    current,
    label = { stringResource(it.label) },
    note = {
        stringResource(
            when (it) {
                WidgetCells.Play -> R.string.widget_cells_play_note
                WidgetCells.All -> R.string.widget_cells_all_note
                WidgetCells.None -> R.string.widget_cells_none_note
            },
        )
    },
    onChoose = onChoose,
)

/** A choice of [values] under [title], each with a line on what it does, the [current] one ticked. */
@Composable
private fun <T> DescribedChoices(
    title: String,
    values: List<T>,
    current: T,
    label: @Composable (T) -> String,
    note: @Composable (T) -> String,
    onChoose: (T) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = sheetEnd(RowLead.None, twoLines = true)),
    ) {
        SheetHeader(title)
        Spacer(Modifier.height(Space.HeaderToRows))
        Column(Modifier.selectableGroup()) {
            values.forEach { value ->
                ListRow(
                    title = label(value),
                    subtitle = note(value),
                    selected = current == value,
                    role = Role.RadioButton,
                    titleLines = 2,
                    subtitleLines = 2,
                    onClick = { onChoose(value) },
                )
            }
        }
    }
}

/** What a choice adds to its name, where it does something a first look would not tell. */
@Composable
private fun note(button: WidgetButton, current: WidgetSlot?, volumeLimit: Int): String? = when (button) {
    WidgetButton.Next -> stringResource(R.string.widget_next_note)

    WidgetButton.Previous -> stringResource(R.string.widget_previous_note)

    WidgetButton.Louder -> volumeLimit.takeIf { it > 0 }?.let {
        stringResource(R.string.widget_louder_note, stringResource(R.string.player_volume_percent, it))
    }

    WidgetButton.Speakers -> stringResource(R.string.widget_speakers_note)

    WidgetButton.ContinueListening -> stringResource(R.string.widget_continue_note)

    WidgetButton.PlayItem -> current?.item?.title ?: stringResource(R.string.widget_something_note)

    else -> null
}

/** A button's row: what it does, a Play … button with its item. */
@Composable
internal fun slotTitle(slot: WidgetSlot): String = slot.item?.takeIf {
    slot.button == WidgetButton.PlayItem
}?.let { stringResource(R.string.widget_play_item, it.title) }
    ?: stringResource(slot.button.label)

/** A look's name. */
internal val WidgetStyle.label: Int
    get() = when (this) {
        WidgetStyle.Cover -> R.string.widget_look_cover
        WidgetStyle.Plain -> R.string.widget_look_plain
        WidgetStyle.Clear -> R.string.widget_look_clear
    }
