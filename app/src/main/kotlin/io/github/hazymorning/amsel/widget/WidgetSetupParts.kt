package io.github.hazymorning.amsel.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.zIndex
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.IconAction
import io.github.hazymorning.amsel.ui.components.InkGlyph
import io.github.hazymorning.amsel.ui.components.Reorderable
import io.github.hazymorning.amsel.ui.components.holdToMove
import io.github.hazymorning.amsel.ui.components.reorderHandle
import io.github.hazymorning.amsel.ui.screens.settings.BetweenRows
import io.github.hazymorning.amsel.ui.screens.settings.SettingRow
import io.github.hazymorning.amsel.ui.screens.settings.SettingSwitch
import io.github.hazymorning.amsel.ui.screens.settings.blockShape
import io.github.hazymorning.amsel.ui.theme.DISABLED_ALPHA
import io.github.hazymorning.amsel.ui.theme.Sizes

/** A row of the widget's page for one of its parts: what plays, or the button at [place] in the saved order. */
@Immutable
internal sealed interface WidgetPart {
    val key: String

    data object Words : WidgetPart {
        override val key = "words"
    }

    data class Button(val place: Int) : WidgetPart {
        override val key get() = "button-$place"
    }
}

/** [buttons] buttons in their saved order, what plays before them or after them as it stands [at]. */
internal fun widgetParts(buttons: Int, at: WidgetWordsPlace): List<WidgetPart> {
    val all = List(buttons) { WidgetPart.Button(it) }
    return if (at == WidgetWordsPlace.After) all + WidgetPart.Words else listOf(WidgetPart.Words) + all
}

/** Where what plays lands when let go at [index] of [parts]: at the nearer end. */
internal fun wordsPlaceAt(index: Int, parts: Int): WidgetWordsPlace =
    if (index < parts / 2f) WidgetWordsPlace.Before else WidgetWordsPlace.After

/**
 * The widget's [parts] in their order, each moved by its handle, a held press anywhere on it, or
 * TalkBack's actions: what plays, before the buttons or after them, shown or left out by its
 * switch; and the buttons, each to change or take away. Those the widget's size cannot hold say so;
 * one more while there are fewer than eight. The widget keeps at least one part.
 */
internal fun LazyListScope.partRows(
    state: WidgetSetupUi,
    parts: List<WidgetPart>,
    reorder: Reorderable,
    onSlot: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onShowWords: (Boolean) -> Unit,
    onMoveWords: (WidgetWordsPlace) -> Unit,
) {
    val slots = state.setup.slots
    val adding = slots.size < MAX_BUTTONS
    val buttons = parts.filterIsInstance<WidgetPart.Button>()
    val words = state.setup.words != WidgetWordsPlace.None
    itemsIndexed(parts, key = { _, part -> part.key }) { index, part ->
        val shape = blockShape(first = index == 0, last = index == parts.lastIndex && !adding)
        Column(lifted(reorder, part.key)) {
            if (index > 0) Spacer(Modifier.height(BetweenRows))
            when (part) {
                WidgetPart.Words -> WordsRow(
                    shown = words,
                    // Without buttons, what plays is all the widget shows.
                    held = words && slots.isEmpty(),
                    first = index == 0,
                    shape = shape,
                    reorder = reorder,
                    onShow = onShowWords,
                    onMove = onMoveWords,
                )

                is WidgetPart.Button -> {
                    val slot = slots.getOrNull(part.place) ?: return@Column
                    val at = buttons.indexOf(part)
                    ButtonRow(
                        slot,
                        part,
                        at,
                        last = buttons.lastIndex,
                        fits = at < state.fits,
                        removable = words || slots.size > 1,
                        shape = shape,
                        reorder = reorder,
                        onSlot = onSlot,
                        onRemove = onRemove,
                        onMove = onMove,
                    )
                }
            }
        }
    }
    if (adding) {
        item(key = ADD) {
            Column {
                Spacer(Modifier.height(BetweenRows))
                SettingRow(
                    stringResource(R.string.widget_add_button),
                    blockShape(first = false, last = true),
                    glyph = R.drawable.ic_add,
                    onClick = { onSlot(slots.size) },
                )
            }
        }
    }
}

/** A row lifted under the finger while it is dragged, or gliding into place. */
@Composable
private fun LazyItemScope.lifted(reorder: Reorderable, key: String): Modifier =
    if (reorder.draggingKey == key || reorder.settlingKey == key) {
        Modifier.zIndex(1f).graphicsLayer { translationY = reorder.offsetOf(key) }
    } else {
        Modifier.animateItem()
    }

/** What plays, its switch and its handle; TalkBack moves it to the other end. */
@Composable
private fun WordsRow(
    shown: Boolean,
    held: Boolean,
    first: Boolean,
    shape: Shape,
    reorder: Reorderable,
    onShow: (Boolean) -> Unit,
    onMove: (WidgetWordsPlace) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val moveTo = if (first) WidgetWordsPlace.After else WidgetWordsPlace.Before
    val move = CustomAccessibilityAction(stringResource(if (first) R.string.move_down else R.string.move_up)) {
        onMove(moveTo)
        true
    }
    val stays = stringResource(R.string.widget_words_stay)
    SettingRow(
        stringResource(R.string.widget_words),
        shape,
        modifier = Modifier
            .holdToMove(
                onStart = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    reorder.start(WidgetPart.Words.key)
                },
                onMove = { reorder.drag(it.y) },
                onEnd = reorder::end,
            )
            .semantics {
                customActions = listOf(move)
                toggleableState = ToggleableState(shown)
                if (held) stateDescription = stays
            },
        subtitle = if (held) stays else null,
        role = Role.Switch,
        glyph = R.drawable.ic_track,
        onClick = {
            if (held) {
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
            } else {
                haptics.performHapticFeedback(if (shown) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                onShow(!shown)
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.alpha(if (held) DISABLED_ALPHA else 1f)) { SettingSwitch(shown) }
                Handle(reorder, WidgetPart.Words.key)
            }
        },
    )
}

@Composable
private fun ButtonRow(
    slot: WidgetSlot,
    part: WidgetPart.Button,
    index: Int,
    last: Int,
    fits: Boolean,
    removable: Boolean,
    shape: Shape,
    reorder: Reorderable,
    onSlot: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val what = slotTitle(slot)
    val up = stringResource(R.string.move_up)
    val down = stringResource(R.string.move_down)
    val moves = listOfNotNull(
        CustomAccessibilityAction(up) {
            onMove(index, index - 1)
            true
        }.takeIf { index > 0 },
        CustomAccessibilityAction(down) {
            onMove(index, index + 1)
            true
        }.takeIf { index < last },
    )
    SettingRow(
        what,
        shape,
        modifier = Modifier
            .holdToMove(
                onStart = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    reorder.start(part.key)
                },
                onMove = { reorder.drag(it.y) },
                onEnd = reorder::end,
            )
            .semantics { customActions = moves },
        // Those the widget's size cannot hold say so where they stand.
        subtitle = if (fits) null else stringResource(R.string.widget_larger),
        glyph = slot.button.glyph,
        onClick = { onSlot(part.place) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (removable) {
                    IconAction(
                        R.drawable.ic_close,
                        stringResource(R.string.widget_remove_button, what),
                        onClick = { onRemove(part.place) },
                    )
                }
                Handle(reorder, part.key)
            }
        },
    )
}

/** A row's handle: a press on it picks the row up at once. */
@Composable
private fun Handle(reorder: Reorderable, key: String) {
    Box(
        Modifier
            .size(Sizes.TouchTarget)
            .reorderHandle(reorder, key)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        InkGlyph(R.drawable.ic_drag, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val ADD = "add"
