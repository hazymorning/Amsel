package io.github.hazymorning.amsel.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import io.github.hazymorning.amsel.ui.theme.Sizes

/** One slow turn; a spinner says "still working", not "hurry". */
private const val TURN_MS = 1_400

/** The waiting arc's sweep, in degrees. */
private const val WAITING_SWEEP = 100f

/** With [progress] the arc stands still and fills (pull to refresh while pulled); without, it turns. */
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    size: Dp = Sizes.Icon,
    color: Color = LocalContentColor.current,
    progress: (() -> Float)? = null,
) {
    val turn = if (progress == null) {
        val transition = rememberInfiniteTransition(label = "spinner")
        transition.animateFloat(
            0f,
            360f,
            infiniteRepeatable(tween(TURN_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "turn",
        )
    } else {
        null
    }
    // Redrawn every frame while it turns; drawWithCache builds what depends only on the size once.
    Spacer(
        modifier.size(size).drawWithCache {
            val width = Sizes.SpinnerStroke.toPx()
            val stroke = Stroke(width, cap = StrokeCap.Round)
            val arcSize = Size(this.size.width - width, this.size.height - width)
            val topLeft = Offset(width / 2f, width / 2f)
            onDrawBehind {
                if (progress != null) {
                    drawArc(color, -90f, 360f * progress().coerceIn(0f, 1f), false, topLeft, arcSize, style = stroke)
                } else {
                    rotate(turn?.value ?: 0f) {
                        drawArc(color, -90f, WAITING_SWEEP, false, topLeft, arcSize, style = stroke)
                    }
                }
            }
        },
    )
}
