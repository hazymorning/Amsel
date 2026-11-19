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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.IconAction
import io.github.hazymorning.amsel.ui.components.InkGlyph
import io.github.hazymorning.amsel.ui.components.Reorderable
import io.github.hazymorning.amsel.ui.components.holdToMove
import io.github.hazymorning.amsel.ui.components.reorderHandle
import io.github.hazymorning.amsel.ui.screens.settings.BetweenRows
import io.github.hazymorning.amsel.ui.screens.settings.SettingRow
import io.github.hazymorning.amsel.ui.screens.settings.blockShape
import io.github.hazymorning.amsel.ui.theme.Sizes

/** A button row's key in the page's list: its place in the order the page last saved. */
internal fun buttonKey(place: Int): String = "button-$place"

/**
 * The buttons in [order] (their places in [slots]), each to change, take away or move: by its
 * handle, a held press anywhere on it, or TalkBack's actions. Those the widget is too small for say
 * so; one more while there are fewer than four.
 */
internal fun LazyListScope.buttonRows(
    slots: List<WidgetSlot>,
    order: List<Int>,
    fits: Int,
    reorder: Reorderable,
    onSlot: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val adding = slots.size < MAX_BUTTONS
    itemsIndexed(order, key = { _, place -> buttonKey(place) }) { index, place ->
        val slot = slots.getOrNull(place) ?: return@itemsIndexed
        val shape = blockShape(first = index == 0, last = index == order.lastIndex && !adding)
        ButtonRow(slot, place, index, order.lastIndex, index < fits, shape, reorder, onSlot, onRemove, onMove)
    }
    if (adding) {
        item(key = ADD) {
            Column {
                if (order.isNotEmpty()) Spacer(Modifier.height(BetweenRows))
                SettingRow(
                    stringResource(R.string.widget_add_button),
                    blockShape(first = order.isEmpty(), last = true),
                    glyph = R.drawable.ic_add,
                    onClick = { onSlot(slots.size) },
                )
            }
        }
    }
}

@Composable
private fun LazyItemScope.ButtonRow(
    slot: WidgetSlot,
    place: Int,
    index: Int,
    last: Int,
    fits: Boolean,
    shape: Shape,
    reorder: Reorderable,
    onSlot: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val key = buttonKey(place)
    val lifted = reorder.draggingKey == key || reorder.settlingKey == key
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
    Column(
        Modifier
            .then(
                if (lifted) {
                    Modifier.zIndex(1f).graphicsLayer { translationY = reorder.offsetOf(key) }
                } else {
                    Modifier.animateItem()
                },
            ),
    ) {
        if (index > 0) Spacer(Modifier.height(BetweenRows))
        SettingRow(
            what,
            shape,
            modifier = Modifier
                .holdToMove(
                    onStart = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        reorder.start(key)
                    },
                    onMove = { reorder.drag(it.y) },
                    onEnd = reorder::end,
                )
                .semantics { customActions = moves },
            // Those the widget's size cannot hold say so where they stand.
            subtitle = if (fits) null else stringResource(R.string.widget_larger),
            glyph = slot.button.glyph,
            onClick = { onSlot(place) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconAction(
                        R.drawable.ic_close,
                        stringResource(R.string.widget_remove_button, what),
                        onClick = { onRemove(place) },
                    )
                    // The handle: a press on it picks the row up at once.
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
            },
        )
    }
}

private const val ADD = "add"
