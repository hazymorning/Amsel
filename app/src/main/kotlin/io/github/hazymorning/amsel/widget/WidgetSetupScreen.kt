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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.components.GroupLabel
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.PrimaryButton
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.capToBaseline
import io.github.hazymorning.amsel.ui.components.moved
import io.github.hazymorning.amsel.ui.components.rememberReorderState
import io.github.hazymorning.amsel.ui.components.trimStartBearing
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
    var sheet by rememberSaveable { mutableStateOf<WidgetSheet?>(null) }
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
        onSheet = { sheet = it },
        onRemove = viewModel::remove,
        onReorder = viewModel::reorder,
        onShowWords = viewModel::showWords,
        onMoveWords = viewModel::moveWords,
        onDone = { viewModel.done(onDone) },
        onBack = onBack,
    )
    val open = sheet ?: return
    AmselSheet(onDismiss = { sheet = null }) {
        when (open) {
            WidgetSheet.Speaker -> SpeakerChoices(state.setup.speaker, state.speakers) { id ->
                close { viewModel.speaker(id) }
            }

            WidgetSheet.Style -> StyleChoices(state.setup.style) { style -> close { viewModel.style(style) } }

            WidgetSheet.Cells -> CellsChoices(state.setup.cells) { cells -> close { viewModel.cells(cells) } }

            is WidgetSheet.Button -> ButtonChoices(
                slot = open.slot,
                current = state.setup.slots.getOrNull(open.slot),
                volumeLimit = state.volumeLimit,
                onChoose = { button ->
                    close {
                        when (button) {
                            WidgetButton.PlayItem -> picking = open.slot
                            else -> viewModel.choose(open.slot, button)
                        }
                    }
                },
            )
        }
    }
}

@Composable
fun WidgetSetupScreen(
    state: WidgetSetupUiState,
    wallpaper: Color?,
    onSheet: (WidgetSheet) -> Unit,
    onRemove: (Int) -> Unit,
    onReorder: (List<Int>) -> Unit,
    onShowWords: (Boolean) -> Unit,
    onMoveWords: (WidgetWordsPosition) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val slots = state.setup.slots
    // The order during a drag; the saved setup replaces it after the drop.
    val at = state.setup.words
    var parts by remember(slots, at) { mutableStateOf(widgetParts(slots, at)) }
    val list = rememberLazyListState()
    val reorder = rememberReorderState(
        list,
        // Only buttons are swap targets: a dragged button never passes the text row.
        movable = { key -> parts.any { it is WidgetPart.Button && it.key == key } },
        onSwap = { from, to ->
            val a = parts.indexOfFirst { it.key == from }
            val b = parts.indexOfFirst { it.key == to }
            parts = parts.moved(a, b)
        },
        onDrop = { key ->
            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            if (key == WidgetPart.Words.key) {
                val place = wordsPositionAt(parts.indexOf(WidgetPart.Words), parts.size)
                parts = widgetParts(slots, place)
                onMoveWords(place)
            } else {
                onReorder(parts.filterIsInstance<WidgetPart.Button>().map { it.place })
            }
        },
    )
    MaterialScreen(modifier, bar = { PageTopBar(stringResource(R.string.widget_setup_title), onBack) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            LazyColumn(Modifier.weight(1f), state = list) {
                item(key = STAGE) { WidgetStage(state, wallpaper) }
                item(key = SETTINGS) {
                    Column(Modifier.padding(top = Space.Group)) { WidgetSettings(state, onSheet) }
                }
                item(key = PARTS) { PartsLabel(state) }
                partRows(
                    state,
                    parts,
                    reorder,
                    onSlot = { onSheet(WidgetSheet.Button(it)) },
                    onRemove = onRemove,
                    onMove = { from, to ->
                        val order = parts.filterIsInstance<WidgetPart.Button>().map { it.place }
                        onReorder(order.moved(from, to))
                    },
                    onShowWords = onShowWords,
                    onMoveWords = onMoveWords,
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

@Composable
private fun WidgetSettings(state: WidgetSetupUiState, onSheet: (WidgetSheet) -> Unit) = SettingsBlock {
    row { shape ->
        val name = state.speakers.firstOrNull { it.id == state.setup.speaker }?.name
            ?: stringResource(R.string.widget_speaker_amsel)
        ValueRow(stringResource(R.string.widget_speaker), name, shape) { onSheet(WidgetSheet.Speaker) }
    }
    row { shape ->
        ValueRow(stringResource(R.string.widget_style), stringResource(state.setup.style.label), shape) {
            onSheet(WidgetSheet.Style)
        }
    }
    row { shape ->
        ValueRow(stringResource(R.string.widget_cells), stringResource(state.setup.cells.label), shape) {
            onSheet(WidgetSheet.Cells)
        }
    }
}

@Composable
private fun PartsLabel(state: WidgetSetupUiState) {
    val hidden = state.setup.slots.size - state.fits
    Column {
        GroupLabel(
            stringResource(R.string.widget_parts),
            above = Space.Group,
            below = if (hidden > 0) Space.S2 else LabelToBlock,
            start = RowWords,
        )
        if (hidden > 0) {
            val hint = if (state.fits == 0) {
                stringResource(R.string.widget_fits_none)
            } else {
                pluralStringResource(R.plurals.widget_fits, state.fits, state.fits)
            }
            WidgetHint(hint, Modifier.padding(start = RowWords, end = Keyline.Margin, bottom = LabelToBlock))
        }
    }
}

@Composable
private fun WidgetHint(text: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyMedium
    Words(
        text,
        style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = Int.MAX_VALUE,
        modifier = modifier.fillMaxWidth().capToBaseline(style).trimStartBearing(text, style),
    )
}

@Composable
private fun WidgetStage(state: WidgetSetupUiState, wallpaper: Color?) {
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
private const val SETTINGS = "settings"
private const val PARTS = "parts"
private const val END = "end"
private val StageShape = SmoothShape(Radius.Large, Radius.Large, Radius.Large, Radius.Large)
