package io.github.hazymorning.amsel.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.theme.Motion
import kotlin.math.abs
import kotlin.math.sign

private const val AWAY_SHARE = 0.3f
private val ThrowSpeed = 400.dp

/** [onGone] is called once it has animated out, let go past [AWAY_SHARE] of its width or thrown. */
@Composable
internal fun Modifier.swipeAway(onGone: () -> Unit): Modifier {
    var offset by remember { mutableFloatStateOf(0f) }
    val throwPx = with(LocalDensity.current) { ThrowSpeed.toPx() }
    var width by remember { mutableIntStateOf(0) }
    return onSizeChanged { width = it.width }
        .draggable(
            rememberDraggableState { dx -> offset += dx },
            Orientation.Horizontal,
            onDragStopped = { velocity ->
                val thrown = abs(velocity) > throwPx && velocity.sign == offset.sign
                if (width > 0 && (thrown || abs(offset) > width * AWAY_SHARE)) {
                    animate(offset, offset.sign * width, velocity, Motion.spatialFast()) { value, _ -> offset = value }
                    onGone()
                } else {
                    animate(offset, 0f, velocity, Motion.spatialSlow()) { value, _ -> offset = value }
                }
            },
        )
        .graphicsLayer {
            translationX = offset
            alpha = if (width > 0) 1f - (abs(offset) / width).coerceIn(0f, 1f) else 1f
        }
}
