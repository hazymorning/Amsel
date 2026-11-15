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
 * Drag to reorder the items of a LazyColumn whose keys [movable] accepts, from a handle. While
 * dragging, items swap through [onSwap] (by key) as the dragged item's edge passes a movable
 * neighbour's middle; items around them (a header, a footer) never take part. A drop that moved the
 * item reports its key.
 */
@Stable
class Reorderable internal constructor(
    private val list: LazyListState,
    private val scope: CoroutineScope,
    private val movable: (Any) -> Boolean,
    private val onSwap: (from: Any, to: Any) -> Unit,
    private val onDrop: (key: Any) -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    /** The item that was just dropped, gliding into its slot. */
    var settlingKey by mutableStateOf<Any?>(null)
        private set
    val settleOffset = Animatable(0f)

    internal val scrolls = Channel<Float>(Channel.CONFLATED)
    private var delta by mutableFloatStateOf(0f)
    private var startOffset by mutableIntStateOf(0)
    private var startIndex = -1

    /** Where the last swap put the dragged item; no new swap until the list has laid it out there. */
    private var pendingIndex: Int? = null

    private val dragged: LazyListItemInfo?
        get() = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingKey }

    /** How far the dragged item is drawn from where the list placed it, so it stays under the finger. */
    val draggingOffset: Float
        get() = dragged?.let { startOffset + delta - it.offset } ?: 0f

    /** Where [key] is drawn from its slot: under the finger, or gliding in after the drop. */
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
            // The list follows its first visible item; pin it by index so it stays put while items swap.
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

/** This list with the item at [from] moved to [to]; the others keep their order. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * Whether the dragged item, drawn from [top] to [bottom], swaps with the item [step] places away (1
 * the next, -1 the previous) that lies at [offset] with [size]: once its leading edge has passed
 * that item's middle. Moving back undoes a swap at the very point it was made, so items of any
 * heights swap once per crossing. A test on the neighbour's span would not: a taller neighbour,
 * moved up into the dragged item's old slot, holds the dragged item's middle again and swaps back.
 */
internal fun swapsWith(top: Float, bottom: Float, step: Int, offset: Int, size: Int): Boolean = when (step) {
    1 -> bottom > offset + size / 2f
    -1 -> top < offset + size / 2f
    else -> false
}

@Composable
fun rememberReorderable(
    list: LazyListState,
    movable: (Any) -> Boolean,
    onSwap: (from: Any, to: Any) -> Unit,
    onDrop: (key: Any) -> Unit,
): Reorderable {
    val scope = rememberCoroutineScope()
    val canMove by rememberUpdatedState(movable)
    val swap by rememberUpdatedState(onSwap)
    val drop by rememberUpdatedState(onDrop)
    val state = remember(list) {
        Reorderable(
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

/** Makes this element the drag handle of the item [key]: a drag in any direction moves the item. */
fun Modifier.reorderHandle(state: Reorderable, key: Any): Modifier = pointerInput(state, key) {
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
 * Picks up what this element stands for once a press has held still for a moment ([onStart]); moving
 * then carries it ([onMove], by how far the finger went) until it is let go ([onEnd]). Until then,
 * taps and presses inside reach their own controls; from the pickup on, this element takes the whole
 * gesture, letting go included, so nothing inside answers it as well.
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
                    // Ahead of everything inside, so a control under the finger never sees the gesture end.
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
