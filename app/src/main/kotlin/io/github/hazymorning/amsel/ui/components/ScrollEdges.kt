package io.github.hazymorning.amsel.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.theme.HAIRLINE_ALPHA
import io.github.hazymorning.amsel.ui.theme.Motion

/** [under]: content has scrolled under the status strip, which then draws its background and hairline. */
@Stable
class ScrollEdgeState {
    var under by mutableStateOf(false)
    internal var owner: Any? = null
}

val LocalTopEdge = staticCompositionLocalOf<ScrollEdgeState?> { null }

@Composable
fun ScrollEdges(state: ScrollableState) {
    val top = LocalTopEdge.current
    LaunchedEffect(state, top) {
        if (top == null) return@LaunchedEffect
        snapshotFlow { state.canScrollBackward }.collect { back ->
            top.owner = state
            top.under = back
        }
    }
    DisposableEffect(state, top) {
        onDispose {
            if (top?.owner === state) {
                top.owner = null
                top.under = false
            }
        }
    }
}

/** Drawn only while content is under the strip, so at rest the page's own background shows through. */
@Composable
fun Modifier.statusBarBackground(edge: ScrollEdgeState): Modifier {
    val colors = MaterialTheme.colorScheme
    val page = colors.surface
    val line = colors.onSurface.copy(alpha = HAIRLINE_ALPHA)
    val shown = animateFloatAsState(if (edge.under) 1f else 0f, Motion.effects(), label = "paper")
    return drawBehind {
        val alpha = shown.value
        if (alpha <= 0f) return@drawBehind
        drawRect(page.copy(alpha = alpha))
        val hairline = HairlineWidth.toPx()
        drawRect(line.copy(alpha = line.alpha * alpha), Offset(0f, size.height - hairline), Size(size.width, hairline))
    }
}

val HairlineWidth = 0.5.dp
