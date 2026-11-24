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

/** No overshoot anywhere; nothing loops but the spinner and the playing ring. */
object Motion {
    const val PRESS_MS = 120
    const val STATE_MS = 250
    const val COVER_MS = 300
    const val NAVIGATION_MS = 350
    const val COLOUR_MS = 700

    const val SHEET_OPEN_MS = 300
    const val SHEET_CLOSE_MS = 240

    const val CROSSFADE_MS = 200

    val Settle = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    val Fade = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    fun <T> spatial(): FiniteAnimationSpec<T> = tween(NAVIGATION_MS, easing = Settle)

    fun <T> spatialFast(): FiniteAnimationSpec<T> = tween(STATE_MS, easing = Settle)

    /** After a release: critically damped, carrying on at the finger's speed; about 450 ms from rest. */
    fun <T> spatialSlow(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 220f)

    fun <T> sheet(open: Boolean): FiniteAnimationSpec<T> =
        tween(if (open) SHEET_OPEN_MS else SHEET_CLOSE_MS, easing = Settle)

    fun <T> sheetFade(): FiniteAnimationSpec<T> = tween(CROSSFADE_MS, easing = LinearEasing)

    fun <T> effects(): FiniteAnimationSpec<T> = tween(STATE_MS, easing = Fade)

    fun <T> effectsFast(): FiniteAnimationSpec<T> = tween(PRESS_MS, easing = Fade)

    fun <T> press(): FiniteAnimationSpec<T> = tween(PRESS_MS, easing = Settle)

    fun <T> effectsSlow(): FiniteAnimationSpec<T> = tween(COLOUR_MS, easing = Fade)

    val AxisTravel = 32.dp

    const val PRESS_SCALE = 0.98f

    /** As in Android's own predictive back. */
    const val BACK_SCALE = 0.9f

    /** A tab is a switch, not a place to travel to, so tabs change without motion. */
    fun none(): ContentTransform = EnterTransition.None togetherWith ExitTransition.None

    fun crossfade(): ContentTransform = fadeIn(effects()) togetherWith fadeOut(effectsFast())

    /**
     * Linear, as the finger drives the progress. [towardRight]: the page drifts right, for a swipe from
     * the left edge.
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

/** Android's animations are off: crossfades only. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Observed, as changing the setting recreates no activity. */
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
