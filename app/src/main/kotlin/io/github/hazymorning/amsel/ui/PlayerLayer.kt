package io.github.hazymorning.amsel.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import io.github.hazymorning.amsel.ui.theme.LocalReducedMotion
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Radius
import io.github.hazymorning.amsel.ui.theme.SmoothShape
import io.github.hazymorning.amsel.ui.theme.Space
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * [fraction] 0 is closed, 1 open. Taps, drags, flings, a list's pull and the back gesture all move
 * this one value, so each can take over from another mid-motion.
 */
@Stable
class PlayerLayerState internal constructor(open: Boolean) {
    private val position = Animatable(if (open) 1f else 0f).apply { updateBounds(0f, 1f) }
    private var drag by mutableFloatStateOf(0f)

    /** The target: true from the moment opening starts, false from the moment closing starts. */
    var isOpen by mutableStateOf(open)
        private set

    val fraction: Float get() = (position.value - drag).coerceIn(0f, 1f)

    val isVisible: Boolean get() = isOpen || fraction > 0f

    var backProgress by mutableFloatStateOf(0f)
        private set

    /** Reduced motion, kept current by [rememberPlayerLayerState]. */
    internal var crossfade = false

    /** Set by [PlayerLayer]. */
    var heightPx by mutableFloatStateOf(0f)
        internal set

    internal val draggable = DraggableState { dragBy(it) }

    /** Takes the pull a list leaves over at its top, so pulling the list down closes the layer. */
    val nestedScroll: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y >= 0f || drag <= 0f) return Offset.Zero
            val before = drag
            dragBy(available.y)
            return Offset(0f, (drag - before) * heightPx)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            dragBy(available.y)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (drag <= 0f) return Velocity.Zero
            settle(available.y)
            return available
        }
    }

    suspend fun open() = settleAt(1f, velocity = 0f, released = false)

    suspend fun close() = settleAt(0f, velocity = 0f, released = false)

    /** Stops the animation, so a finger can catch a layer that is still settling. */
    suspend fun hold() = position.stop()

    /** Positive [deltaPx] (down) moves toward closed. Clamped at both ends, so turning back answers at once. */
    fun dragBy(deltaPx: Float) {
        if (heightPx > 0f) drag = (drag + deltaPx / heightPx).coerceIn(position.value - 1f, position.value)
    }

    /**
     * [velocityPx] is in px/s, down positive. Opening from the dock takes a shorter pull and a slower
     * flick than closing, as a pull up has no other meaning there.
     */
    suspend fun settle(velocityPx: Float) {
        val speed = if (heightPx > 0f) -velocityPx / heightPx else 0f
        val target = when {
            speed > (if (isOpen) THROW_SPEED else PULL_SPEED) -> 1f
            speed < -THROW_SPEED -> 0f
            isOpen -> if (fraction < 1f - MOVE_ON) 0f else 1f
            else -> if (fraction > PULL_ON) 1f else 0f
        }
        settleAt(target, speed, released = true)
    }

    fun followBack(progress: Float) {
        backProgress = progress.coerceIn(0f, 1f)
    }

    private suspend fun settleAt(target: Float, velocity: Float, released: Boolean) = coroutineScope {
        isOpen = target == 1f
        val from = fraction
        drag = 0f
        position.snapTo(from)
        // A cancelled back preview animates away as the layer reopens; a committed one holds until closed.
        if (target == 1f && backProgress > 0f) {
            launch { animate(backProgress, 0f, animationSpec = Motion.spatial()) { v, _ -> backProgress = v } }
        }
        val spec: AnimationSpec<Float> = when {
            crossfade -> Motion.sheetFade()
            released -> Motion.spatialSlow()
            else -> Motion.sheet(open = target == 1f)
        }
        position.animateTo(target, spec, velocity)
        if (target == 0f) backProgress = 0f
    }

    companion object {
        private const val THROW_SPEED = 0.5f
        private const val MOVE_ON = 0.33f
        private const val PULL_SPEED = 0.2f
        private const val PULL_ON = 0.125f

        val Saver: Saver<PlayerLayerState, Boolean> = Saver(save = { it.isOpen }, restore = { PlayerLayerState(it) })
    }
}

@Composable
fun rememberPlayerLayerState(): PlayerLayerState {
    val state = rememberSaveable(saver = PlayerLayerState.Saver) { PlayerLayerState(open = false) }
    val still = LocalReducedMotion.current
    SideEffect { state.crossfade = still }
    return state
}

/** Vertical only, so sliders and swipeable rows keep their drags; a child that scrolls takes its drags first. */
fun Modifier.layerDrag(state: PlayerLayerState): Modifier = draggable(
    state = state.draggable,
    orientation = Orientation.Vertical,
    onDragStarted = { state.hold() },
    onDragStopped = { velocity -> state.settle(velocity) },
)

/** The share of travel from fully open over which the top corners reach a sheet's radius. */
private const val CORNER_RAMP = 0.125f

/** The back preview's inset at each side, within the page margins, so no page content shows beside it. */
private val BackInset = Space.Sibling

@Composable
fun PlayerLayer(state: PlayerLayerState, onClose: () -> Unit, content: @Composable () -> Unit) {
    // The handler's coroutine is cancelled with the gesture, so reopening runs in this scope.
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled = state.isOpen) { events ->
        try {
            events.collect { event -> state.followBack(event.progress) }
            onClose()
        } catch (cancelled: CancellationException) {
            scope.launch { state.open() }
            throw cancelled
        }
    }
    val visible by remember(state) { derivedStateOf { state.isVisible } }
    // Always measured, so a drag from the mini player has the height before the layer shows.
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { state.heightPx = it.height.toFloat() },
    ) {
        if (visible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val fraction = state.fraction
                        val back = state.backProgress
                        val scale = if (size.width > 0f) 1f - 2f * BackInset.toPx() * back / size.width else 1f
                        scaleX = scale
                        scaleY = scale
                        // Scaling is about the centre; the extra offset keeps the bottom edge on the screen's.
                        val travel = if (state.crossfade) 0f else (1f - fraction) * size.height
                        translationY = travel + (1f - scale) * size.height / 2f
                        if (state.crossfade) alpha = fraction
                        val round = maxOf((1f - fraction) / CORNER_RAMP, back).coerceIn(0f, 1f)
                        if (round > 0f) {
                            // The clip scales with the layer, so the radius divides by the scale. The
                            // bottom is at or below the screen's edge, so it stays square.
                            shape = SmoothShape.top(Radius.Large * round / scale)
                            clip = true
                        }
                    },
            ) { content() }
        }
    }
}
