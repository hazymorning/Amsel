package io.github.hazymorning.amsel.widget

import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.components.GroupLabel
import io.github.hazymorning.amsel.ui.components.InkGlyph
import io.github.hazymorning.amsel.ui.components.ListRow
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.PrimaryButton
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.sheetEnd
import io.github.hazymorning.amsel.ui.screens.settings.PageHint
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space
import kotlinx.coroutines.flow.map

/** The names of the four buttons, from the left. */
private val SlotNames =
    listOf(R.string.widget_slot_1, R.string.widget_slot_2, R.string.widget_slot_3, R.string.widget_slot_4)

/** Widget [widgetId]'s page, [size] as the launcher gives it; Play … picks with Search on a page of its own. */
@Composable
fun WidgetSetupRoute(widgetId: Int, size: DpSize, onDone: () -> Unit, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val viewModel = viewModel {
        WidgetSetupViewModel(widgetId, graph.widgets, graph.data.prefs.prefs.map { it?.volumeLimit ?: 0 })
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<Int?>(null) }
    var picking by rememberSaveable { mutableStateOf<Int?>(null) }
    val pickingSlot = picking
    if (pickingSlot != null) {
        BackHandler { picking = null }
        WidgetPicker(
            onPick = {
                viewModel.pick(pickingSlot, it)
                picking = null
            },
            onBack = { picking = null },
        )
        return
    }
    WidgetSetupScreen(
        state,
        widgetId,
        size,
        onSlot = { sheet = it },
        onDone = { viewModel.done(onDone) },
        onBack = onBack,
    )
    val open = sheet ?: return
    AmselSheet(onDismiss = { sheet = null }) {
        ButtonChoices(
            slot = open,
            current = state.slots[open],
            volumeLimit = state.volumeLimit,
            onChoose = { button ->
                close {
                    if (button == WidgetButton.PlayItem) picking = open else viewModel.choose(open, button)
                }
            },
        )
    }
}

/** The widget as it will look, and its four buttons as rows; a tap on either changes a button. */
@Composable
fun WidgetSetupScreen(
    state: WidgetSetupUi,
    widgetId: Int,
    size: DpSize,
    onSlot: (Int) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MaterialScreen(modifier, bar = { PageTopBar(stringResource(R.string.widget_setup_title), onBack) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                PageHint(stringResource(R.string.widget_setup_hint))
                WidgetPicture(state, widgetId, size, onSlot, Modifier.align(Alignment.CenterHorizontally))
                GroupLabel(
                    stringResource(R.string.widget_setup_buttons),
                    above = Space.S8,
                    below = Space.LabelToContent,
                )
                state.slots.forEachIndexed { index, slot ->
                    val what = slotTitle(slot)
                    val change = stringResource(R.string.widget_change_slot, stringResource(SlotNames[index]), what)
                    ListRow(
                        title = what,
                        lead = RowLead.Glyph,
                        leading = { InkGlyph(slot.button.glyph, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { onSlot(index) },
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = change
                            role = Role.Button
                            onClick {
                                onSlot(index)
                                true
                            }
                        },
                    )
                }
            }
            PrimaryButton(
                stringResource(R.string.widget_setup_done),
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Keyline.Margin, vertical = Space.Group),
            )
        }
    }
}

/**
 * The widget itself, drawn from the same views the launcher shows, at its size there. Its buttons
 * choose here rather than act.
 */
@Composable
private fun WidgetPicture(
    state: WidgetSetupUi,
    widgetId: Int,
    size: DpSize,
    onSlot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val labels = remember(context) { WidgetViews(context) }
    val views = remember(state, widgetId) {
        labels.build(widgetId, state.look, state.slots, state.cover, state.thumbs, taps = false)
    }
    Box(modifier.padding(top = Space.Group).size(size)) {
        AndroidView(
            factory = { FrameLayout(it) },
            update = { frame ->
                frame.removeAllViews()
                frame.addView(views.apply(frame.context, frame))
            },
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            Modifier.align(
                Alignment.BottomCenter,
            ).fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 4.dp).height(52.dp),
        ) {
            state.slots.forEachIndexed { index, slot ->
                val change = stringResource(
                    R.string.widget_change_slot,
                    stringResource(SlotNames[index]),
                    labels.label(slot, state.look),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(role = Role.Button, onClickLabel = null) { onSlot(index) }
                        .clearAndSetSemantics {
                            contentDescription = change
                            role = Role.Button
                            onClick {
                                onSlot(index)
                                true
                            }
                        },
                )
            }
        }
    }
}

/** Button [slot]'s choices, the [current] one ticked; Play … goes on to pick its item. */
@Composable
internal fun ButtonChoices(slot: Int, current: WidgetSlot, volumeLimit: Int, onChoose: (WidgetButton) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = sheetEnd(RowLead.Glyph, twoLines = true)),
    ) {
        SheetHeader(stringResource(SlotNames[slot]))
        Spacer(Modifier.height(Space.HeaderToRows))
        Column(Modifier.selectableGroup()) {
            WidgetButton.entries.forEach { button ->
                ListRow(
                    title = stringResource(button.label),
                    subtitle = note(button, current, volumeLimit),
                    lead = RowLead.Glyph,
                    leading = { InkGlyph(button.glyph, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    selected = current.button == button,
                    role = Role.RadioButton,
                    titleLines = 2,
                    subtitleLines = 2,
                    onClick = { onChoose(button) },
                )
            }
        }
    }
}

/** What a choice adds to its name, where it does something a first look would not tell. */
@Composable
private fun note(button: WidgetButton, current: WidgetSlot, volumeLimit: Int): String? = when (button) {
    WidgetButton.Next -> stringResource(R.string.widget_next_note)

    WidgetButton.Previous -> stringResource(R.string.widget_previous_note)

    WidgetButton.Louder -> volumeLimit.takeIf { it > 0 }?.let {
        stringResource(R.string.widget_louder_note, stringResource(R.string.player_volume_percent, it))
    }

    WidgetButton.Speakers -> stringResource(R.string.widget_speakers_note)

    WidgetButton.ContinueListening -> stringResource(R.string.widget_continue_note)

    WidgetButton.PlayItem -> current.item?.title ?: stringResource(R.string.widget_something_note)

    else -> null
}

/** A button's row: what it does, a Play … button with its item. */
@Composable
private fun slotTitle(slot: WidgetSlot): String = slot.item?.takeIf {
    slot.button == WidgetButton.PlayItem
}?.let { stringResource(R.string.widget_play_item, it.title) }
    ?: stringResource(slot.button.label)
