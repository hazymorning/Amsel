package io.github.hazymorning.amsel.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.theme.Motion
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Finger travel past which a release goes to the next or previous. */
private val GoDistance = 64.dp

/** The share of the finger's travel the content follows, so it lags behind the finger. */
private const val FOLLOW = 0.6f

/** The lowest alpha the content fades to, while dragged or after a swipe that holds. */
private const val FAINTEST = 0.4f

/** How long a change of selection still counts as the swipe's result. */
private const val GOING_MS = 2_000L

/**
 * A swipe for the next or previous, always beside a visible control that does the same (a skip, a
 * chip). [detect] goes where the finger may start, [follow] on the content that moves with it.
 */
@Stable
class SideSwipeState internal constructor(
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
    private val goPx: Float,
) {
    private var travel by mutableFloatStateOf(0f)
    private var shown by mutableFloatStateOf(1f)

    /** Where the content stood at touch down, possibly mid-animation; a swipe is measured from there. */
    private var from = 0f
    private var settling: Job? = null
    private var forgetting: Job? = null

    /** Direction of an accepted swipe (1 forward, -1 back) until its result arrives, else 0. */
    internal var going = 0
        private set

    internal var enabled = true
    internal var rtl = false

    /**
     * An accepted swipe moves the content further out, faint, until its result arrives, rather than
     * returning first: for a selection whose neighbours are known (an order, the Library's kinds).
     */
    internal var holds = false

    internal var canGo: (forward: Boolean) -> Boolean = { true }
    internal var onGo: (forward: Boolean) -> Unit = {}

    /** Read only while drawing, so a swipe recomposes nothing. */
    val follow: Modifier = Modifier.graphicsLayer {
        translationX = travel
        alpha = shown
    }

    val detect: Modifier = Modifier.pointerInput(this) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!enabled) return@awaitEachGesture
            from = travel
            val first = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                change.consume()
                drag(over)
            } ?: return@awaitEachGesture
            val released = horizontalDrag(first.id) { change ->
                drag(change.positionChange().x)
                change.consume()
            }
            if (released) release() else rest()
        }
    }

    private fun drag(dx: Float) {
        settling?.cancel()
        travel += dx * FOLLOW
        shown = 1f - (abs(travel) / (goPx * 2 * FOLLOW)).coerceIn(0f, 1f - FAINTEST)
    }

    private fun release() {
        val moved = travel - from
        val forward = (moved < 0) != rtl
        if (abs(moved) >= goPx * FOLLOW && canGo(forward)) {
            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            going = if (forward) 1 else -1
            forgetting?.cancel()
            forgetting = scope.launch {
                delay(GOING_MS)
                going = 0
                if (holds) rest()
            }
            onGo(forward)
            if (holds) return leave()
        }
        rest()
    }

    private fun leave() = settle {
        val out = (travel - from).sign * goPx * 2 * FOLLOW
        launch { animate(shown, FAINTEST, animationSpec = Motion.effects()) { value, _ -> shown = value } }
        animate(travel, out, animationSpec = Motion.spatial()) { value, _ -> travel = value }
    }

    private fun settle(to: suspend CoroutineScope.() -> Unit) {
        settling?.cancel()
        settling = scope.launch(block = to)
    }

    private fun rest() = settle {
        launch { animate(shown, 1f, animationSpec = Motion.effects()) { value, _ -> shown = value } }
        animate(travel, 0f, animationSpec = Motion.spatialSlow()) { value, _ -> travel = value }
    }

    /** The result has arrived: it fades in where it stands, without sliding. */
    internal fun enter() = settle {
        going = 0
        travel = 0f
        // With [holds] the old content stayed in sight at [FAINTEST]; carry on from that alpha.
        shown = if (holds) FAINTEST else 0f
        launch { animate(shown, 1f, animationSpec = Motion.effects()) { value, _ -> shown = value } }
        animate(travel, 0f, animationSpec = Motion.spatial()) { value, _ -> travel = value }
    }
}

/**
 * With an [order], swipes stop at its ends unless [endless], and every change of [selected] fades in,
 * a tapped chip's too. Without one (what plays), only a change a swipe brought fades in.
 */
@Composable
fun <T> rememberSideSwipeState(
    selected: T,
    order: List<T>? = null,
    enabled: Boolean = true,
    endless: Boolean = false,
    onGo: (forward: Boolean) -> Unit,
): SideSwipeState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val swipe = remember(density, haptics) {
        with(density) { SideSwipeState(scope, haptics, GoDistance.toPx()) }
    }
    SideEffect {
        swipe.enabled = enabled
        swipe.rtl = rtl
        swipe.holds = order != null
        swipe.onGo = onGo
        swipe.canGo = { forward ->
            val index = order?.indexOf(selected)
            when {
                index == null -> true
                endless -> order.size > 1
                forward -> index < order.lastIndex
                else -> index > 0
            }
        }
    }
    var last by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected == last) return@LaunchedEffect
        val step = order?.let { stepBetween(it, last, selected, swipe.going) } ?: swipe.going
        last = selected
        if (step != 0) swipe.enter()
    }
    return swipe
}

/**
 * The direction from [from] to [to]: the swipe's own ([going]) where it led there, even round the end
 * of [order]; else by their positions.
 */
internal fun <T> stepBetween(order: List<T>, from: T, to: T, going: Int): Int {
    val start = order.indexOf(from)
    if (going != 0 && start >= 0 && order[(start + going).mod(order.size)] == to) return going
    return (order.indexOf(to) - start).sign
}
