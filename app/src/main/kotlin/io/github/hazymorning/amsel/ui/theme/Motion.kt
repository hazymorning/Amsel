package io.github.hazymorning.amsel.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Motion like breathing: unhurried, settling without overshoot, and only where a person moved
 * something; nothing loops but a spinner and the playing ring's breath. Where a finger hands over
 * its speed, a critically damped spring carries it on, so a gesture settles at its own pace.
 */
object Motion {
    const val PRESS_MS = 120
    const val STATE_MS = 250
    const val COVER_MS = 300
    const val NAVIGATION_MS = 350
    const val COLOUR_MS = 700

    /** Now Playing and Up next rise as one sheet in this time, and fall back in [SHEET_CLOSE_MS]. */
    const val SHEET_OPEN_MS = 300
    const val SHEET_CLOSE_MS = 240

    /** With reduced motion a sheet crossfades in this time. */
    const val CROSSFADE_MS = 200

    /** Starts at once and settles softly, never past its target. */
    val Settle = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** For opacity and colour, which have no momentum to show. */
    val Fade = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** Pages, sheets and things that move. */
    fun <T> spatial(): FiniteAnimationSpec<T> = tween(NAVIGATION_MS, easing = Settle)

    /** A state change that moves something small: the slider's handle growing, a new song's words. */
    fun <T> spatialFast(): FiniteAnimationSpec<T> = tween(STATE_MS, easing = Settle)

    /** After a finger lets go: no bounce, at the gesture's own speed. About 450 ms from rest. */
    fun <T> spatialSlow(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 220f)

    /** Opening ([open]) and closing a sheet of the player from a tap. */
    fun <T> sheet(open: Boolean): FiniteAnimationSpec<T> =
        tween(if (open) SHEET_OPEN_MS else SHEET_CLOSE_MS, easing = Settle)

    /** A sheet with reduced motion. */
    fun <T> sheetFade(): FiniteAnimationSpec<T> = tween(CROSSFADE_MS, easing = LinearEasing)

    /** State changes in colour and opacity. */
    fun <T> effects(): FiniteAnimationSpec<T> = tween(STATE_MS, easing = Fade)

    /** What leaves. */
    fun <T> effectsFast(): FiniteAnimationSpec<T> = tween(PRESS_MS, easing = Fade)

    /** A press, in and out again: the highlight, and the control sinking. */
    fun <T> press(): FiniteAnimationSpec<T> = tween(PRESS_MS, easing = Settle)

    /** Colour that follows the artwork: the glow, a card's tint. */
    fun <T> effectsSlow(): FiniteAnimationSpec<T> = tween(COLOUR_MS, easing = Fade)

    /** How far a page drifts under the back gesture, which the finger moves. */
    val AxisTravel = 32.dp

    /** A pressed control sinks this much. */
    const val PRESS_SCALE = 0.98f

    /** How small the page under a back gesture gets, as in Android's own predictive back. */
    const val BACK_SCALE = 0.9f

    /** Between tabs: a tab is a switch, not a place to travel to, so the other one is there at once. */
    fun none(): ContentTransform = EnterTransition.None togetherWith ExitTransition.None

    /** Content that changes, a page that opens or closes included: a short crossfade, nothing that travels. */
    fun crossfade(): ContentTransform = fadeIn(effects()) togetherWith fadeOut(effectsFast())

    /**
     * The predictive back gesture: the page shrinks and drifts toward the side the swipe comes
     * from while the page below fades up. Linear, because the finger drives its progress;
     * [towardRight] is true for a swipe from the left edge.
     */
    fun predictiveBack(travelPx: Int, towardRight: Boolean): ContentTransform {
        val follow = tween<Float>(durationMillis = NAVIGATION_MS, easing = LinearEasing)
        val drift = tween<IntOffset>(durationMillis = NAVIGATION_MS, easing = LinearEasing)
        return (fadeIn(follow) + slideInHorizontally(drift) { -travelPx / 2 }) togetherWith
            (
                scaleOut(follow, targetScale = BACK_SCALE) +
                    slideOutHorizontally(drift) { if (towardRight) abs(travelPx) else -abs(travelPx) } +
                    fadeOut(follow)
                )
    }
}

/** True when the person asked Android to remove animations: crossfades only. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Follows the setting while the app is open: changing it recreates nothing. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val reduced by produceState(resolver.animationsOff(), resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                value = resolver.animationsOff()
            }
        }
        val scale = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        resolver.registerContentObserver(scale, false, observer)
        awaitDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

private fun ContentResolver.animationsOff(): Boolean =
    Settings.Global.getFloat(this, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
