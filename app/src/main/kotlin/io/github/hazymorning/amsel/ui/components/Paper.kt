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

/** Whether content runs under the status strip, so its paper and hairline show; see [ScrollEdges]. */
@Stable
class EdgeState {
    var under by mutableStateOf(false)
    internal var owner: Any? = null
}

val LocalTopEdge = staticCompositionLocalOf<EdgeState?> { null }

/** Tells the status strip whether [state]'s content runs under it. */
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

/**
 * The page's own paper under the status bar (and a slim bar on it), solid, with a hairline along its
 * lower edge. Both come only while content runs under it ([edge]), so at rest a page's wash shows
 * through and nothing marks the top of the screen.
 */
@Composable
fun Modifier.statusPaper(edge: EdgeState): Modifier {
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

/** Half a dp: the hairline under the status strip, on the dock and inside every cover. */
val HairlineWidth = 0.5.dp
