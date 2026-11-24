package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.data.home.SectionLayout
import io.github.hazymorning.amsel.ui.components.MediaRow
import io.github.hazymorning.amsel.ui.components.MediaTile
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.holdToMove
import io.github.hazymorning.amsel.ui.components.moved
import io.github.hazymorning.amsel.ui.components.overhang
import io.github.hazymorning.amsel.ui.components.rowGlyphBottom
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Motion
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** [visible] is the visible area in window coordinates. */
internal class DragScrollArea(val scroll: ScrollableState, val vertical: Boolean, val visible: () -> Rect)

@Composable
internal fun PinArrangement(
    section: SectionUi,
    onOpen: (MediaUi) -> Unit,
    onArrange: (List<String>) -> Unit,
    home: DragScrollArea?,
    modifier: Modifier = Modifier,
) {
    val items = section.items
    val keys = items.map { it.key }
    val scope = rememberCoroutineScope()
    val drag = remember { PinDragState(scope) }
    LaunchedEffect(keys) { drag.sync(keys) }
    val shelf = section.layout.isShelf
    val shelfScroll = rememberScrollState()
    var shelfBox by remember { mutableStateOf(Rect.Zero) }
    val rooms by rememberUpdatedState(
        listOfNotNull(home, DragScrollArea(shelfScroll, vertical = false) { shelfBox }.takeIf { shelf }),
    )
    val tile = tileWidth(section.layout)
    val pin: @Composable (Int, MediaUi) -> Unit = { index, item ->
        Pin(item, index, keys, section, drag, onOpen, onArrange)
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val inner = maxWidth - Keyline.Margin * 2
        when (section.layout) {
            SectionLayout.Grid -> {
                val columns = cardColumns(inner)
                PinSlots(
                    drag,
                    keys,
                    SlotGrid.Spec(
                        columns,
                        (inner - CardGap * (columns - 1)) / columns,
                        CardGap,
                        CardGap,
                        Keyline.Margin,
                    ),
                    shelf = false,
                ) { items.forEachIndexed { index, item -> key(item.key) { pin(index, item) } } }
            }

            SectionLayout.Small, SectionLayout.Row, SectionLayout.Big -> Box(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { shelfBox = it.boundsInWindow() }
                    .horizontalScroll(shelfScroll),
            ) {
                val gap = tileGap(section.layout)
                PinSlots(drag, keys, SlotGrid.Spec(keys.size, tile, gap, 0.dp, Keyline.Margin), shelf = true) {
                    items.forEachIndexed { index, item -> key(item.key) { pin(index, item) } }
                }
            }

            SectionLayout.List -> PinSlots(
                drag,
                keys,
                SlotGrid.Spec(1, maxWidth, 0.dp, 0.dp, 0.dp),
                shelf = false,
                Modifier.overhang(bottom = rowGlyphBottom(RowLead.Cover, twoLines = true)),
            ) { items.forEachIndexed { index, item -> key(item.key) { pin(index, item) } } }
        }
    }
    val carrying = drag.dragging != null
    val density = LocalDensity.current
    LaunchedEffect(carrying, density) {
        if (!carrying) return@LaunchedEffect
        val edge = with(density) { CarryEdge.toPx() }
        val most = with(density) { CarrySpeed.toPx() }
        while (true) {
            withFrameNanos {}
            val box = drag.box() ?: continue
            if (!drag.moved) continue
            for (room in rooms) {
                val speed = room.speed(box, edge, most)
                if (speed == 0f) continue
                val used = room.scroll.scrollBy(speed)
                drag.carry(if (room.vertical) Offset(0f, used) else Offset(used, 0f))
            }
        }
    }
}

@Composable
private fun Pin(
    item: MediaUi,
    index: Int,
    keys: List<String>,
    section: SectionUi,
    drag: PinDragState,
    onOpen: (MediaUi) -> Unit,
    onArrange: (List<String>) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val lifted = drag.dragging == item.key
    val scale by animateFloatAsState(if (lifted) LIFT else 1f, Motion.press(), label = "lift")
    val earlier = stringResource(R.string.move_earlier)
    val later = stringResource(R.string.move_later)
    val move = { to: Int ->
        onArrange(keys.moved(index, to))
        true
    }
    val actions = buildList {
        if (index > 0) add(CustomAccessibilityAction(earlier) { move(index - 1) })
        if (index < keys.lastIndex) add(CustomAccessibilityAction(later) { move(index + 1) })
    }
    val modifier = Modifier
        .holdToMove(
            onStart = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                drag.start(item.key)
            },
            onMove = drag::move,
            onEnd = { drag.end()?.let(onArrange) },
        )
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .semantics { customActions = actions }
    val open = { onOpen(item) }
    when (section.layout) {
        SectionLayout.Grid -> HomeCard(item, onClick = open, onMore = null, modifier = modifier)

        SectionLayout.Small, SectionLayout.Row, SectionLayout.Big ->
            MediaTile(
                item,
                onClick = open,
                onMore = null,
                modifier = modifier,
                twoLines = section.option.twoLineCaptions,
            )

        SectionLayout.List -> MediaRow(item, onClick = open, onMore = null, modifier = modifier)
    }
}

// Pins are placed by [drag]'s order and never reordered in composition, so the carried pin keeps
// its gesture.
@Composable
private fun PinSlots(
    drag: PinDragState,
    keys: List<String>,
    slots: SlotGrid.Spec,
    shelf: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier.onGloballyPositioned { drag.origin = it.positionInWindow() }) { measurables, constraints ->
        val cell = slots.cell.roundToPx()
        val placeables = measurables.map { it.measure(Constraints(minWidth = cell, maxWidth = cell)) }
        val grid = SlotGrid(
            columns = slots.columns,
            cell = cell,
            height = placeables.maxOfOrNull { it.height } ?: 0,
            gapX = slots.gapX.roundToPx(),
            gapY = slots.gapY.roundToPx(),
            origin = slots.origin.roundToPx(),
            count = keys.size,
        )
        drag.measured(grid)
        val width = if (shelf) grid.width else constraints.maxWidth
        layout(width, grid.totalHeight) {
            placeables.forEachIndexed { i, placeable ->
                val key = keys.getOrNull(i) ?: return@forEachIndexed
                val at = drag.position(key, keys)
                placeable.place(at.x.roundToInt(), at.y.roundToInt(), zIndex = if (drag.dragging == key) 1f else 0f)
            }
        }
    }
}

// In pixels; [Spec] holds the layout's request in dp.
private data class SlotGrid(
    val columns: Int,
    val cell: Int,
    val height: Int,
    val gapX: Int,
    val gapY: Int,
    val origin: Int,
    val count: Int,
) {
    data class Spec(val columns: Int, val cell: Dp, val gapX: Dp, val gapY: Dp, val origin: Dp)

    private val rows: Int get() = if (count == 0) 0 else (count + columns - 1) / columns

    val width: Int get() = origin * 2 + count * cell + (count - 1).coerceAtLeast(0) * gapX

    val totalHeight: Int get() = rows * height + (rows - 1).coerceAtLeast(0) * gapY

    val size: Size get() = Size(cell.toFloat(), height.toFloat())

    fun at(index: Int): Offset {
        val i = index.coerceAtLeast(0)
        return Offset((origin + i % columns * (cell + gapX)).toFloat(), (i / columns * (height + gapY)).toFloat())
    }

    fun nearest(centre: Offset): Int {
        if (count == 0) return 0
        val column = floor((centre.x - origin + gapX / 2f) / (cell + gapX)).toInt().coerceIn(0, columns - 1)
        val row = floor((centre.y + gapY / 2f) / (height + gapY)).toInt().coerceIn(0, rows - 1)
        return (row * columns + column).coerceIn(0, count - 1)
    }

    companion object {
        val None = SlotGrid(1, 0, 0, 0, 0, 0, 0)
    }
}

@Stable
private class PinDragState(private val scope: CoroutineScope) {
    private var order by mutableStateOf(emptyList<String>())
    private var before = emptyList<String>()
    private var at by mutableStateOf(Offset.Zero)
    private var slots = SlotGrid.None
    private val shown = HashMap<String, Animatable<Offset, AnimationVector2D>>()
    private val targets = HashMap<String, Offset>()

    var dragging by mutableStateOf<String?>(null)
        private set

    /** The finger has moved the carried pin since pickup; scrolling under it does not count. */
    var moved = false
        private set

    var origin = Offset.Zero

    fun sync(keys: List<String>) {
        if (dragging == null) order = keys
    }

    fun measured(next: SlotGrid) {
        if (next == slots) return
        slots = next
        // A new slot size (rotation, font scale): pins snap to their slots instead of animating.
        if (dragging == null) {
            shown.clear()
            targets.clear()
        }
    }

    private fun arranged(keys: List<String>): List<String> =
        order.takeIf { it.size == keys.size && it.toSet() == keys.toSet() } ?: keys

    fun position(key: String, keys: List<String>): Offset {
        if (key == dragging) return at
        val slot = slots.at(arranged(keys).indexOf(key))
        val glide = shown.getOrPut(key) { Animatable(slot, Offset.VectorConverter) }
        if (targets[key] != slot) {
            targets[key] = slot
            scope.launch { glide.animateTo(slot, Motion.spatialFast()) }
        }
        return glide.value
    }

    fun start(key: String) {
        before = order
        at = shown[key]?.value ?: slots.at(order.indexOf(key))
        moved = false
        dragging = key
    }

    fun move(by: Offset) {
        at += by
        moved = true
        reorder()
    }

    /** Offsets the carried pin by what its container scrolled, so it stays under the finger. */
    fun carry(by: Offset) {
        at += by
        reorder()
    }

    private fun reorder() {
        val key = dragging ?: return
        val from = order.indexOf(key)
        val to = slots.nearest(at + Offset(slots.size.width / 2f, slots.size.height / 2f))
        if (from >= 0 && to != from) order = order.moved(from, to)
    }

    /** Returns the new order, or null if it did not change. */
    fun end(): List<String>? {
        val key = dragging ?: return null
        val slot = slots.at(order.indexOf(key))
        val glide = shown.getOrPut(key) { Animatable(at, Offset.VectorConverter) }
        targets[key] = slot
        val from = at
        // Undispatched, so no frame draws the pin at its position from before the pickup.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            glide.snapTo(from)
            glide.animateTo(slot, Motion.spatialFast())
        }
        dragging = null
        return order.takeIf { it != before }
    }

    /** The carried pin's bounds in window coordinates. */
    fun box(): Rect? = if (dragging == null) null else Rect(origin + at, slots.size)
}

// Pixels per frame, rising to [most] as the pin's middle comes within [edge] of an end.
private fun DragScrollArea.speed(box: Rect, edge: Float, most: Float): Float {
    val shows = visible()
    if (shows.isEmpty) return 0f
    val middle = if (vertical) box.center.y else box.center.x
    val start = if (vertical) shows.top else shows.left
    val end = if (vertical) shows.bottom else shows.right
    return when {
        middle > end - edge -> most * ((middle - (end - edge)) / edge).coerceAtMost(1f)
        middle < start + edge -> -most * ((start + edge - middle) / edge).coerceAtMost(1f)
        else -> 0f
    }
}

private val CarryEdge = 64.dp

private val CarrySpeed = 8.dp

private const val LIFT = 1.04f
