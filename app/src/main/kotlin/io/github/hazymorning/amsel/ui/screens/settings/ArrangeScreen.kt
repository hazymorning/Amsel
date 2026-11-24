package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import io.github.hazymorning.amsel.ui.components.AlignedGlyph
import io.github.hazymorning.amsel.ui.components.ListRow
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.components.holdToMove
import io.github.hazymorning.amsel.ui.components.rememberReorderState
import io.github.hazymorning.amsel.ui.components.reorderHandle
import io.github.hazymorning.amsel.ui.navigation.OrderedToggles
import io.github.hazymorning.amsel.ui.theme.DISABLED_ALPHA
import io.github.hazymorning.amsel.ui.theme.Sizes

/**
 * A switch never changes the order, so no row jumps. [fixed] always stays and has no switch. With none
 * fixed, the last row shown cannot be switched off ([fixedLine]); with [max] shown, the others cannot be
 * switched on ([fullLine]).
 */
@Composable
internal fun <T> ArrangeScreen(
    title: String,
    hint: String,
    arrangement: OrderedToggles<T>,
    key: (T) -> String,
    label: @Composable (T) -> String,
    icon: (T) -> Int,
    fixed: T?,
    fixedLine: String,
    onChange: (OrderedToggles<T>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    max: Int = Int.MAX_VALUE,
    fullLine: String? = null,
    note: (T) -> String? = { null },
) {
    val haptics = LocalHapticFeedback.current
    // The local order while a drag swaps rows; the stored one replaces it once the drop is saved.
    var order by remember(arrangement.order) { mutableStateOf(arrangement.order) }
    val list = rememberLazyListState()
    val keys = arrangement.order.map(key).toSet()
    val reorder = rememberReorderState(
        list,
        movable = { it in keys },
        onSwap = { from, to ->
            val a = order.indexOfFirst { key(it) == from }
            val b = order.indexOfFirst { key(it) == to }
            if (a >= 0 && b >= 0) order = order.toMutableList().apply { add(b, removeAt(a)) }
        },
        onDrop = {
            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            onChange(arrangement.copy(order = order))
        },
    )
    val up = stringResource(R.string.move_up)
    val down = stringResource(R.string.move_down)
    val full = arrangement.shown.size >= max
    MaterialScreen(modifier, bar = { PageTopBar(title, onBack) }) { insets ->
        ScrollEdges(list)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = insets) {
            item(key = HINT) { PageHint(hint) }
            items(order, key = key) { item ->
                val id = key(item)
                val index = order.indexOf(item)
                val lifted = reorder.draggingKey == id || reorder.settlingKey == id
                val on = item in arrangement.shown
                val waits = !on && full
                val last = fixed == null && on && arrangement.shown.size == 1
                val held = waits || last
                val moves = listOfNotNull(
                    CustomAccessibilityAction(up) {
                        onChange(arrangement.moved(index, index - 1))
                        true
                    }.takeIf { index > 0 },
                    CustomAccessibilityAction(down) {
                        onChange(arrangement.moved(index, index + 1))
                        true
                    }.takeIf { index < order.lastIndex },
                )
                ListRow(
                    title = label(item),
                    modifier = Modifier
                        .then(
                            if (lifted) {
                                Modifier.zIndex(1f).graphicsLayer { translationY = reorder.offsetOf(id) }
                            } else {
                                Modifier.animateItem()
                            },
                        )
                        .holdToMove(
                            onStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                reorder.start(id)
                            },
                            onMove = { reorder.drag(it.y) },
                            onEnd = reorder::end,
                        )
                        .semantics {
                            customActions = moves
                            if (item != fixed) toggleableState = ToggleableState(on)
                            if (waits && fullLine != null) stateDescription = fullLine
                            if (last) stateDescription = fixedLine
                        },
                    subtitle = if (item == fixed) fixedLine else note(item),
                    // Never cut, at any font size, as in every setting.
                    titleLines = Int.MAX_VALUE,
                    subtitleLines = Int.MAX_VALUE,
                    lead = RowLead.Glyph,
                    role = if (item == fixed) Role.Button else Role.Switch,
                    onClick = when {
                        item == fixed -> null

                        // A held row stays a switch for TalkBack and answers a tap with a reject haptic.
                        held -> ({ haptics.performHapticFeedback(HapticFeedbackType.Reject) })

                        else -> (
                            {
                                haptics.performHapticFeedback(
                                    if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                                )
                                onChange(arrangement.with(item, !on))
                            }
                            )
                    },
                    leading = { AlignedGlyph(icon(item), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (item != fixed) {
                                Box(Modifier.alpha(if (held) DISABLED_ALPHA else 1f)) { SettingSwitch(on) }
                            }
                            Box(
                                Modifier
                                    .size(Sizes.TouchTarget)
                                    .reorderHandle(reorder, id)
                                    .clearAndSetSemantics {},
                                contentAlignment = Alignment.Center,
                            ) {
                                AlignedGlyph(R.drawable.ic_drag, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                )
            }
        }
    }
}

private const val HINT = "hint"
