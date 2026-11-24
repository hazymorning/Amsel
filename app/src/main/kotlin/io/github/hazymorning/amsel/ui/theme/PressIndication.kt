package io.github.hazymorning.amsel.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

private const val HIGHLIGHT_ALPHA = 0.06f

@Stable
class PressIndication(private val color: Color, private val shape: Shape?, private val sink: Boolean = true) :
    IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressNode(interactionSource, color, shape, sink)

    override fun equals(other: Any?): Boolean =
        other is PressIndication && other.color == color && other.shape == shape && other.sink == sink

    override fun hashCode(): Int = (color.hashCode() * 31 + shape.hashCode()) * 31 + sink.hashCode()
}

private class PressNode(
    private val source: InteractionSource,
    private val color: Color,
    private val shape: Shape?,
    private val sink: Boolean,
) : Modifier.Node(),
    DrawModifierNode {
    private val pressed = Animatable(0f)
    private var outline: Outline? = null
    private var outlineFor = Size.Unspecified

    override fun onAttach() {
        coroutineScope.launch {
            var down = 0
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> down++
                    is PressInteraction.Release, is PressInteraction.Cancel -> down = (down - 1).coerceAtLeast(0)
                    else -> return@collect
                }
                val target = if (down > 0) 1f else 0f
                launch {
                    pressed.animateTo(target, Motion.press()) { invalidateDraw() }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = pressed.value
        if (p <= 0f) {
            drawContent()
            return
        }
        val factor = if (sink) 1f - (1f - Motion.PRESS_SCALE) * p else 1f
        scale(factor) {
            if (shape != null) {
                if (outlineFor != size) {
                    outline = shape.createOutline(size, layoutDirection, this)
                    outlineFor = size
                }
                outline?.let { drawOutline(it, color, alpha = HIGHLIGHT_ALPHA * p) }
            }
            this@draw.drawContent()
        }
    }
}

/** A null [shape] keeps only the sink, for a tile whose cover shows the press. */
@Composable
fun rememberPress(shape: Shape?, sink: Boolean = true): PressIndication {
    val ink = MaterialTheme.colorScheme.onSurface
    return remember(ink, shape, sink) { PressIndication(ink, shape, sink) }
}
