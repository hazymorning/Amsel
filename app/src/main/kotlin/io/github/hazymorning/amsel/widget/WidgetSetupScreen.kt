package io.github.hazymorning.amsel.widget

import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.components.ChoiceChipRow
import io.github.hazymorning.amsel.ui.components.GroupLabel
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.PrimaryButton
import io.github.hazymorning.amsel.ui.components.rememberReorderable
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.screens.settings.LabelToBlock
import io.github.hazymorning.amsel.ui.screens.settings.RowWords
import io.github.hazymorning.amsel.ui.screens.settings.SettingsBlock
import io.github.hazymorning.amsel.ui.screens.settings.ValueRow
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Radius
import io.github.hazymorning.amsel.ui.theme.SmoothShape
import io.github.hazymorning.amsel.ui.theme.Space
import kotlinx.coroutines.flow.map

/** Widget [widgetId]'s page; Play … picks with Search on a page of its own. */
@Composable
fun WidgetSetupRoute(widgetId: Int, wallpaper: Color?, onDone: () -> Unit, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val viewModel = viewModel {
        WidgetSetupViewModel(
            widgetId,
            graph.widgets,
            speakers = graph.playing.speakers.map { all ->
                all.filterNot { it.isPhone }.map { WidgetSpeaker(it.id, it.name) }
            },
            volumeLimit = graph.data.prefs.prefs.map { it?.volumeLimit ?: 0 },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<Int?>(null) }
    var choosingSpeaker by rememberSaveable { mutableStateOf(false) }
    var choosingCells by rememberSaveable { mutableStateOf(false) }
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
        wallpaper,
        onStyle = viewModel::style,
        onSpeaker = { choosingSpeaker = true },
        onCells = { choosingCells = true },
        onSlot = { sheet = it },
        onRemove = viewModel::remove,
        onReorder = viewModel::reorder,
        onDone = { viewModel.done(onDone) },
        onBack = onBack,
    )
    val open = sheet
    if (open != null) {
        AmselSheet(onDismiss = { sheet = null }) {
            ButtonChoices(
                slot = open,
                current = state.setup.slots.getOrNull(open),
                volumeLimit = state.volumeLimit,
                onChoose = { button ->
                    close {
                        if (button == WidgetButton.PlayItem) picking = open else viewModel.choose(open, button)
                    }
                },
            )
        }
    }
    if (choosingSpeaker) {
        AmselSheet(onDismiss = { choosingSpeaker = false }) {
            SpeakerChoices(state.setup.speaker, state.speakers) { id -> close { viewModel.speaker(id) } }
        }
    }
    if (choosingCells) {
        AmselSheet(onDismiss = { choosingCells = false }) {
            CellsChoices(state.setup.cells) { cells -> close { viewModel.cells(cells) } }
        }
    }
}

/**
 * The widget as it will look, at its size on the home screen, over the wallpaper's own colour; under
 * it how it looks, which speaker it keeps to, and its buttons, which can be none, in an order the
 * person moves them into.
 */
@Composable
fun WidgetSetupScreen(
    state: WidgetSetupUi,
    wallpaper: Color?,
    onStyle: (WidgetStyle) -> Unit,
    onSpeaker: () -> Unit,
    onCells: () -> Unit,
    onSlot: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onReorder: (List<Int>) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val slots = state.setup.slots
    // The order while a drag swaps rows, as places in the saved one; the saved order replaces it on drop.
    var order by remember(slots) { mutableStateOf(slots.indices.toList()) }
    val list = rememberLazyListState()
    val reorder = rememberReorderable(
        list,
        movable = { key -> order.any { buttonKey(it) == key } },
        onSwap = { from, to ->
            val a = order.indexOfFirst { buttonKey(it) == from }
            val b = order.indexOfFirst { buttonKey(it) == to }
            if (a >= 0 && b >= 0) order = order.toMutableList().apply { add(b, removeAt(a)) }
        },
        onDrop = {
            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            onReorder(order)
        },
    )
    MaterialScreen(modifier, bar = { PageTopBar(stringResource(R.string.widget_setup_title), onBack) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            LazyColumn(Modifier.weight(1f), state = list) {
                item(key = STAGE) { WidgetStage(state, wallpaper) }
                item(key = LOOK) {
                    ChoiceChipRow(
                        WidgetStyle.entries,
                        selected = state.setup.style,
                        onSelect = onStyle,
                        label = { stringResource(it.label) },
                        role = Role.RadioButton,
                        modifier = Modifier.padding(top = Space.Group, bottom = Space.Group),
                    )
                }
                item(key = SPEAKER) {
                    SettingsBlock {
                        row { shape ->
                            val name = state.speakers.firstOrNull { it.id == state.setup.speaker }?.name
                                ?: stringResource(R.string.widget_speaker_amsel)
                            ValueRow(stringResource(R.string.widget_speaker), name, shape, onSpeaker)
                        }
                        row { shape ->
                            ValueRow(
                                stringResource(R.string.widget_cells),
                                stringResource(state.setup.cells.label),
                                shape,
                                onCells,
                            )
                        }
                    }
                }
                item(key = BUTTONS) {
                    val fits = pluralStringResource(R.plurals.widget_fits, state.fits, state.fits)
                    GroupLabel(
                        stringResource(R.string.widget_buttons) + DOT_SEPARATOR + fits,
                        above = Space.S8,
                        below = LabelToBlock,
                        start = RowWords,
                    )
                }
                buttonRows(
                    slots,
                    order,
                    state.fits,
                    reorder,
                    onSlot = onSlot,
                    onRemove = onRemove,
                    onMove = { from, to -> onReorder(order.toMutableList().apply { add(to, removeAt(from)) }) },
                )
                item(key = END) { Spacer(Modifier.height(Space.Group)) }
            }
            PrimaryButton(
                stringResource(R.string.widget_setup_done),
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Keyline.Margin, vertical = Space.Group),
            )
        }
    }
}

/** The widget drawn from the same views the launcher shows, at its size there; nothing on it acts here. */
@Composable
private fun WidgetStage(state: WidgetSetupUi, wallpaper: Color?) {
    val picture = state.picture
    val ground = wallpaper ?: MaterialTheme.colorScheme.surfaceContainer
    Box(
        Modifier
            .padding(horizontal = Keyline.Margin)
            .padding(top = Space.S4)
            .fillMaxWidth()
            .clip(StageShape)
            .background(ground)
            .padding(vertical = StageRoom)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.requiredSize(state.size.width.dp, state.size.height.dp)) {
            if (picture != null) {
                AndroidView(
                    factory = { FrameLayout(it) },
                    update = { frame ->
                        frame.removeAllViews()
                        frame.addView(picture.apply(frame.context, frame))
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private val StageRoom = 32.dp
private const val STAGE = "stage"
private const val LOOK = "look"
private const val SPEAKER = "speaker"
private const val BUTTONS = "buttons"
private const val END = "end"
private val StageShape = SmoothShape(Radius.Large, Radius.Large, Radius.Large, Radius.Large)
