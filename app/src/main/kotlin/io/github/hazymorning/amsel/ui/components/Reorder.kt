package io.github.hazymorning.amsel.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import io.github.hazymorning.amsel.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * [onSwap] is called during the drag, by key, each time the dragged item's edge passes a movable
 * neighbour's middle; no further swap happens until the list shows it. [onDrop] only if the item moved.
 */
@Stable
class ReorderState internal constructor(
    private val list: LazyListState,
    private val scope: CoroutineScope,
    private val movable: (Any) -> Boolean,
    private val onSwap: (from: Any, to: Any) -> Unit,
    private val onDrop: (key: Any) -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    /** The dropped item while it animates into its slot. */
    var settlingKey by mutableStateOf<Any?>(null)
        private set
    val settleOffset = Animatable(0f)

    internal val scrolls = Channel<Float>(Channel.CONFLATED)
    private var delta by mutableFloatStateOf(0f)
    private var startOffset by mutableIntStateOf(0)
    private var startIndex = -1

    /** Where the last swap put the dragged item; no further swap until the list has laid it out there. */
    private var pendingIndex: Int? = null

    private val dragged: LazyListItemInfo?
        get() = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingKey }

    /** The translation from the list's slot that keeps the dragged item under the finger. */
    val draggingOffset: Float
        get() = dragged?.let { startOffset + delta - it.offset } ?: 0f

    fun offsetOf(key: Any): Float = when (key) {
        draggingKey -> draggingOffset
        settlingKey -> settleOffset.value
        else -> 0f
    }

    fun start(key: Any) {
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        draggingKey = key
        startOffset = info.offset
        startIndex = info.index
        pendingIndex = null
        delta = 0f
    }

    fun drag(dy: Float) {
        delta += dy
        val item = dragged ?: return
        if (pendingIndex != null && item.index != pendingIndex) return
        pendingIndex = null
        val top = item.offset + draggingOffset
        val bottom = top + item.size
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != item.key && movable(it.key) && swapsWith(top, bottom, it.index - item.index, it.offset, it.size)
        }
        if (target != null) {
            // The list keeps its first visible item's key in place; pin it by index so a swap there does not scroll.
            if (item.index == list.firstVisibleItemIndex || target.index == list.firstVisibleItemIndex) {
                list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
            }
            onSwap(item.key, target.key)
            pendingIndex = target.index
        } else {
            val overscroll = when {
                delta > 0 -> (bottom - list.layoutInfo.viewportEndOffset).coerceAtLeast(0f)
                delta < 0 -> (top - list.layoutInfo.viewportStartOffset).coerceAtMost(0f)
                else -> 0f
            }
            if (overscroll != 0f) scrolls.trySend(overscroll)
        }
    }

    fun end() {
        val key = draggingKey ?: return
        val endIndex = dragged?.index ?: startIndex
        val offset = draggingOffset
        draggingKey = null
        delta = 0f
        settlingKey = key
        scope.launch {
            settleOffset.snapTo(offset)
            settleOffset.animateTo(0f, Motion.spatial())
            if (settlingKey == key) settlingKey = null
        }
        if (endIndex != startIndex) onDrop(key)
    }
}

fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * Swaps once the dragged item's leading edge passes the middle of the neighbour [step] away (1 next,
 * -1 previous), so items of any height swap once per crossing. Testing the neighbour's span instead,
 * a taller neighbour moved into the dragged item's old slot would hold its middle and swap straight back.
 */
internal fun swapsWith(top: Float, bottom: Float, step: Int, offset: Int, size: Int): Boolean = when (step) {
    1 -> bottom > offset + size / 2f
    -1 -> top < offset + size / 2f
    else -> false
}

@Composable
fun rememberReorderState(
    list: LazyListState,
    movable: (Any) -> Boolean,
    onSwap: (from: Any, to: Any) -> Unit,
    onDrop: (key: Any) -> Unit,
): ReorderState {
    val scope = rememberCoroutineScope()
    val canMove by rememberUpdatedState(movable)
    val swap by rememberUpdatedState(onSwap)
    val drop by rememberUpdatedState(onDrop)
    val state = remember(list) {
        ReorderState(
            list,
            scope,
            movable = { canMove(it) },
            onSwap = { from, to -> swap(from, to) },
            onDrop = { drop(it) },
        )
    }
    LaunchedEffect(state) {
        for (by in state.scrolls) list.scrollBy(by)
    }
    return state
}

fun Modifier.reorderHandle(state: ReorderState, key: Any): Modifier = pointerInput(state, key) {
    detectDragGestures(
        onDragStart = { state.start(key) },
        onDrag = { change, amount ->
            change.consume()
            state.drag(amount.y)
        },
        onDragEnd = state::end,
        onDragCancel = state::end,
    )
}

/**
 * After a long press ([onStart]), [onMove] gets each finger delta until release ([onEnd]). Before it,
 * taps reach the controls inside; from the pickup on this element consumes the whole gesture, release included.
 */
@Composable
fun Modifier.holdToMove(
    enabled: Boolean = true,
    onStart: () -> Unit,
    onMove: (Offset) -> Unit,
    onEnd: () -> Unit,
): Modifier {
    if (!enabled) return this
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            start()
            try {
                while (true) {
                    // Initial pass, ahead of the children, so a control under the finger never sees the release.
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == held.id }
                        ?: break
                    val moved = change.positionChange()
                    change.consume()
                    if (!change.pressed) break
                    if (moved != Offset.Zero) move(moved)
                }
            } finally {
                end()
            }
        }
    }
}
