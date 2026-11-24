package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.components.overhang
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Sizes

/**
 * Lays out as the line; the 48 dp touch band overhangs it. Only a tap or a sideways drag moves it:
 * vertical swipes and long presses, taken as moves, once sent a book back to its chapter's start.
 * [fraction] is read only while drawing; a TalkBack adjustment calls [onChange], then [onChangeFinished].
 */
@Composable
internal fun LineSlider(
    fraction: () -> Float,
    semanticValue: Float,
    label: String,
    stateDescription: String,
    onChange: (Float) -> Unit,
    onChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    restingThumb: Boolean = true,
    pending: Boolean = false,
    onChangeCancelled: () -> Unit = {},
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    // Composition follows only whether the thumb is held; each move is drawn, not composed.
    val held by remember { derivedStateOf { dragging != null } }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onChangeFinished)
    val cancelled by rememberUpdatedState(onChangeCancelled)
    val haptics = LocalHapticFeedback.current
    val ink = MaterialTheme.colorScheme.onSurface
    val played = animateFloatAsState(if (pending) PENDING_ALPHA else 1f, Motion.effects(), label = "played")
    val resting = if (restingThumb) Sizes.Thumb else 0.dp
    val thumb = animateDpAsState(if (held) Sizes.ThumbHeld else resting, Motion.spatialFast(), label = "thumb")
    val gestures = if (!enabled) {
        Modifier
    } else {
        Modifier.pointerInput(rtl) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val sideways = awaitHorizontalTouchSlopOrCancellation(down.id) { move, _ -> move.consume() }
                if (sideways == null) {
                    val up = currentEvent.changes.firstOrNull { it.id == down.id }
                    val tap = up != null && up.changedToUp() &&
                        up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis &&
                        (up.position - down.position).getDistance() < viewConfiguration.touchSlop
                    if (tap) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        fractionAt(down.position.x, size.width, rtl).let {
                            change(it)
                            finished(it)
                        }
                    }
                    return@awaitEachGesture
                }
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                dragging = fractionAt(sideways.position.x, size.width, rtl).also(change)
                // A drag taken over by another gesture, or lost midway, is cancelled, not finished.
                var done = false
                try {
                    val lifted = horizontalDrag(sideways.id) { move ->
                        dragging = fractionAt(move.position.x, size.width, rtl).also(change)
                        move.consume()
                    }
                    if (lifted) {
                        dragging?.let(finished)
                        done = true
                    }
                } finally {
                    dragging = null
                    if (!done) cancelled()
                }
            }
        }
    }
    val reach = (Sizes.TouchTarget - Sizes.SliderTrack) / 2
    Spacer(
        modifier
            .overhang(top = reach, bottom = reach)
            .fillMaxWidth()
            .height(Sizes.TouchTarget)
            .sliderSemantics(label, stateDescription, semanticValue, enabled, change, finished)
            .then(gestures)
            // Its own layer, as a playing line redraws every frame.
            .graphicsLayer()
            .drawWithCache {
                val y = size.height / 2f
                val stroke = Sizes.SliderTrack.toPx()
                val left = stroke / 2f
                val right = size.width - stroke / 2f
                val rest = ink.copy(alpha = REST_ALPHA)
                // A resting thumb stays whole at 0% and 100%; without one the line runs to its ends.
                val inset = if (restingThumb) Sizes.Thumb.toPx() / 2f else left
                onDrawBehind {
                    if (!enabled) {
                        drawLine(rest, Offset(left, y), Offset(right, y), stroke, StrokeCap.Round)
                        return@onDrawBehind
                    }
                    val f = (dragging ?: fraction()).coerceIn(0f, 1f)
                    val along = inset + f * (size.width - 2 * inset)
                    withTransform({ if (rtl) scale(-1f, 1f) }) {
                        drawLine(rest, Offset(left, y), Offset(right, y), stroke, StrokeCap.Round)
                        if (along > left) {
                            // While held, the value is the finger's: full strength, even while pending.
                            val tone = if (dragging != null) ink else ink.copy(alpha = played.value)
                            drawLine(tone, Offset(left, y), Offset(along, y), stroke, StrokeCap.Round)
                        }
                        if (thumb.value > 0.dp) drawCircle(ink, thumb.value.toPx() / 2f, Offset(along, y))
                    }
                }
            },
    )
}

private fun fractionAt(x: Float, width: Int, rtl: Boolean): Float {
    val w = width.toFloat().coerceAtLeast(1f)
    return ((if (rtl) w - x else x) / w).coerceIn(0f, 1f)
}

private fun Modifier.sliderSemantics(
    label: String,
    stateDescription: String,
    value: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    onChangeFinished: (Float) -> Unit,
): Modifier = semantics {
    contentDescription = label
    this.stateDescription = stateDescription
    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
    if (enabled) {
        setProgress { target ->
            target.coerceIn(0f, 1f).let {
                onChange(it)
                onChangeFinished(it)
            }
            true
        }
    } else {
        disabled()
    }
}

private const val REST_ALPHA = 0.12f

// The played part while the speaker has not reached the value yet.
private const val PENDING_ALPHA = 0.45f
