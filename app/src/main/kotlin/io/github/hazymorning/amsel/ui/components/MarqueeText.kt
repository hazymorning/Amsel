package io.github.hazymorning.amsel.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.theme.LocalReducedMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * A line too long for its room moves back and forth as in MarqueeLabel's `leftRight` mode, pausing at
 * each end, while it [runs]; with reduced motion it stays at its start.
 */
@Composable
fun MarqueeText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, runs: Boolean = true) {
    val measurer = rememberTextMeasurer()
    val line = remember(text, style, measurer) { measurer.measure(text, style, maxLines = 1, softWrap = false) }
    val rtl = line.getParagraphDirection(0) == ResolvedTextDirection.Rtl
    val still = LocalReducedMotion.current || !runs
    val speed = with(LocalDensity.current) { Speed.toPx() } / MS_PER_SECOND
    // Read only while drawing, so the movement never recomposes.
    val moved = remember(line) { Animatable(0f) }
    var room by remember { mutableIntStateOf(0) }
    LaunchedEffect(line, still, speed) {
        snapshotFlow { room }.collectLatest { width ->
            moved.snapTo(0f)
            val hidden = line.size.width - width
            if (width == 0 || hidden <= 0 || still) return@collectLatest
            val glide = tween<Float>((hidden / speed).roundToInt(), easing = EaseInOut)
            while (true) {
                delay(REST_MS)
                moved.animateTo(hidden.toFloat(), glide)
                delay(REST_MS)
                moved.animateTo(0f, glide)
            }
        }
    }
    Layout(
        modifier = modifier
            .onSizeChanged { room = it.width }
            .semantics { this.text = AnnotatedString(text) }
            // Offscreen, so DstIn fades only the text, not what lies under it.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                val hidden = line.size.width - size.width
                // A right-to-left line starts at its right edge.
                val x = if (rtl) moved.value - hidden else -moved.value
                clipRect { drawText(line, color = color, topLeft = Offset(x, 0f)) }
                if (hidden <= 0f) return@drawWithContent
                val fade = FadeWidth.toPx().coerceAtMost(size.width / 2)
                fadeEdge(fade, left = true, strength = (-x / fade).coerceIn(0f, 1f))
                fadeEdge(fade, left = false, strength = ((x + line.size.width - size.width) / fade).coerceIn(0f, 1f))
            },
    ) { _, constraints ->
        val width = minOf(line.size.width, constraints.maxWidth).coerceAtLeast(constraints.minWidth)
        val lines = mapOf<AlignmentLine, Int>(
            FirstBaseline to line.firstBaseline.roundToInt(),
            LastBaseline to line.lastBaseline.roundToInt(),
        )
        layout(width, line.size.height, lines) {}
    }
}

private fun DrawScope.fadeEdge(fade: Float, left: Boolean, strength: Float) {
    if (strength <= 0f) return
    val clear = Color.Black.copy(alpha = 1f - strength)
    drawRect(
        brush = Brush.horizontalGradient(
            colors = if (left) listOf(clear, Color.Black) else listOf(Color.Black, clear),
            startX = if (left) 0f else size.width - fade,
            endX = if (left) fade else size.width,
        ),
        topLeft = Offset(if (left) 0f else size.width - fade, 0f),
        size = Size(fade, size.height),
        blendMode = BlendMode.DstIn,
    )
}

private val FadeWidth = 20.dp

/** A glide's average speed per second, the same as Android's and Compose's own marquees. */
private val Speed = 30.dp
private const val MS_PER_SECOND = 1000f

/** The pause at each end, long enough to read what has come into view. */
private const val REST_MS = 2_500L
