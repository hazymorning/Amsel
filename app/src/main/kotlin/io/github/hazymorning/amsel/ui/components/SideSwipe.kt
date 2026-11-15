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

/** A release this far along goes on to the next, or back. */
private val GoDistance = 64.dp

/** How much of the finger's travel the content follows: it lags a little, as if held back. */
private const val FOLLOW = 0.6f

/** How faint the content gets under the finger, at twice [GoDistance]. */
private const val FAINTEST = 0.4f

/** How long what a swipe asked for may take to come before it is no longer the swipe's. */
private const val GOING_MS = 2_000L

/**
 * A sideways swipe that asks for the next of something, or the one before, as a visible control
 * does too (a skip, a chip): [detect] goes where the finger may start, [follow] on what moves with
 * it, lagging and fading. A release past [GoDistance] acts, with a tick, and what then takes its
 * place comes in from the side it lies on, along the pages' own short axis.
 */
@Stable
class SideSwipe internal constructor(
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
    private val goPx: Float,
) {
    private var travel by mutableFloatStateOf(0f)
    private var shown by mutableFloatStateOf(1f)

    /** Where the content stood when the finger came down: a swipe is measured from there. */
    private var from = 0f
    private var settling: Job? = null
    private var forgetting: Job? = null

    /** The way of a swipe let go past the mark, until what it asked for is there: 1 forward, -1 back. */
    internal var going = 0
        private set

    internal var enabled = true
    internal var rtl = false

    /**
     * Whether a swipe that acts carries on out of sight, faint, until what it asked for comes, rather
     * than going back first: for a chosen one whose neighbours are at hand (the Library's kinds).
     */
    internal var holds = false

    /** Whether there is a next ([forward]) or one before, and the asking for it. */
    internal var canGo: (forward: Boolean) -> Boolean = { true }
    internal var onGo: (forward: Boolean) -> Unit = {}

    /** For what moves with the finger. Read while drawing: a swipe recomposes nothing. */
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

    /** On out the way it went, fading to the faintest, while the next is on its way. */
    private fun leave() = settle {
        val out = (travel - from).sign * goPx * 2 * FOLLOW
        launch { animate(shown, FAINTEST, animationSpec = Motion.effects()) { value, _ -> shown = value } }
        animate(travel, out, animationSpec = Motion.spatial()) { value, _ -> travel = value }
    }

    private fun settle(to: suspend CoroutineScope.() -> Unit) {
        settling?.cancel()
        settling = scope.launch(block = to)
    }

    /** Back to rest, at the finger's own pace. */
    private fun rest() = settle {
        launch { animate(shown, 1f, animationSpec = Motion.effects()) { value, _ -> shown = value } }
        animate(travel, 0f, animationSpec = Motion.spatialSlow()) { value, _ -> travel = value }
    }

    /** What was asked for is there: it fades in where it stands, for no finger moves it. */
    internal fun enter() = settle {
        going = 0
        travel = 0f
        // Where the last one stays in sight until this comes, this takes over from it as faint as it was.
        shown = if (holds) FAINTEST else 0f
        launch { animate(shown, 1f, animationSpec = Motion.effects()) { value, _ -> shown = value } }
        animate(travel, 0f, animationSpec = Motion.spatial()) { value, _ -> travel = value }
    }
}

/**
 * A [SideSwipe] over [selected]. With an [order], a swipe goes to [selected]'s neighbour in it and
 * every change of [selected] comes in from its side, a tapped chip's too. It stops at either end,
 * or, [endless], goes round: past the last to the first, which then comes in the swipe's own way.
 * Without an order (what plays), a swipe asks for the next or the one before while [enabled], and
 * only what a swipe brought comes in from the side. [onGo] is told which way: true for the next.
 */
@Composable
fun <T> rememberSideSwipe(
    selected: T,
    order: List<T>? = null,
    enabled: Boolean = true,
    endless: Boolean = false,
    onGo: (forward: Boolean) -> Unit,
): SideSwipe {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val swipe = remember(density, haptics) {
        with(density) { SideSwipe(scope, haptics, GoDistance.toPx()) }
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
 * The side [to] comes in from after [from]: the way of the swipe that led there, which round the
 * end of [order] is not the side its place lies on; else that side.
 */
internal fun <T> stepBetween(order: List<T>, from: T, to: T, going: Int): Int {
    val start = order.indexOf(from)
    if (going != 0 && start >= 0 && order[(start + going).mod(order.size)] == to) return going
    return (order.indexOf(to) - start).sign
}
