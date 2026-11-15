package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import io.github.hazymorning.amsel.ui.theme.Fade

/** Each edge of a column fades only while there is more to scroll that way, growing in over the first [length]. */
fun Modifier.fadingEdges(state: LazyListState, length: Dp = Fade.Short): Modifier =
    fade(length, vertical = true) { px ->
        val info = state.layoutInfo
        val start = if (state.firstVisibleItemIndex > 0) 1f else state.firstVisibleItemScrollOffset / px
        val last = info.visibleItemsInfo.lastOrNull()
        val end = when {
            last == null -> 0f
            last.index < info.totalItemsCount - 1 -> 1f
            else -> (last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)) / px
        }
        start to end
    }

/** [fadingEdges] for a plain scrolling row. */
fun Modifier.fadingEdges(state: ScrollState): Modifier =
    fade(Fade.Long, vertical = false) { px -> state.value / px to (state.maxValue - state.value) / px }

/**
 * Offscreen, so the mask touches only this content, not what is behind it. The bands are built once
 * per size; each frame only sets how strongly they take the content away.
 */
private fun Modifier.fade(length: Dp, vertical: Boolean, amounts: (Float) -> Pair<Float, Float>): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val px = length.toPx()
            val extent = if (vertical) size.height else size.width
            val fade = minOf(px, extent / 2)
            val band = if (vertical) Size(size.width, fade) else Size(fade, size.height)
            val startBand = edgeBrush(vertical, from = 0f, to = fade)
            val endBand = edgeBrush(vertical, from = extent, to = extent - fade)
            val endAt = if (vertical) Offset(0f, extent - fade) else Offset(extent - fade, 0f)
            onDrawWithContent {
                drawContent()
                if (px <= 0f) return@onDrawWithContent
                val (rawStart, rawEnd) = amounts(px)
                // A row's start is on the right in right-to-left layouts.
                val mirrored = !vertical && layoutDirection == LayoutDirection.Rtl
                val start = (if (mirrored) rawEnd else rawStart).coerceIn(0f, 1f)
                val end = (if (mirrored) rawStart else rawEnd).coerceIn(0f, 1f)
                if (start > 0f) drawRect(startBand, Offset.Zero, band, alpha = start, blendMode = BlendMode.DstOut)
                if (end > 0f) drawRect(endBand, endAt, band, alpha = end, blendMode = BlendMode.DstOut)
            }
        }

/** Opaque at [from] (the edge), clear at [to]; drawn out of the content, it thins it toward the edge. */
private fun edgeBrush(vertical: Boolean, from: Float, to: Float): Brush {
    val colors = listOf(Color.Black, Color.Transparent)
    return if (vertical) {
        Brush.verticalGradient(colors, startY = from, endY = to)
    } else {
        Brush.horizontalGradient(colors, startX = from, endX = to)
    }
}
