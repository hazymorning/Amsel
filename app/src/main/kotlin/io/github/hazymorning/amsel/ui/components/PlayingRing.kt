package io.github.hazymorning.amsel.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.hazymorning.amsel.ui.theme.LocalReducedMotion
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Sizes

/** Whether music plays on the speaker in use, from the app's root: a list's playing ring breathes only then. */
val LocalMusicPlays = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/**
 * The thin moss ring that marks what is playing, beside it in a list. While the music plays
 * ([moving]) it breathes: an echo of it widens and fades, slowly, then rests before the next.
 * Paused, or with reduced motion, it holds still.
 */
@Composable
fun PlayingRing(modifier: Modifier = Modifier, moving: Boolean = LocalMusicPlays.current.value) {
    val moss = MaterialTheme.colorScheme.tertiary
    val echo = if (moving && !LocalReducedMotion.current) {
        rememberInfiniteTransition(label = "ring")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(BREATH_MS, easing = LinearEasing)), label = "echo")
    } else {
        null
    }
    Canvas(modifier.size(Sizes.Ring)) {
        val stroke = Stroke(Sizes.RingStroke.toPx())
        val radius = size.minDimension / 2f
        echo?.value?.let { time ->
            // It leaves from under the ring itself, settling as it widens, and is gone before the rest.
            val out = (time / OUT_SHARE).coerceAtMost(1f)
            val reach = 1f + GROWTH * Motion.Settle.transform(out)
            drawRing(moss.copy(alpha = ECHO_ALPHA * (1f - out)), stroke, center, radius * reach)
        }
        drawRing(moss, stroke, center, radius)
    }
}

/** A ring of [stroke] whose outer edge has [radius], around [centre]; [fill] closes its middle. */
fun DrawScope.drawRing(color: Color, stroke: Stroke, centre: Offset, radius: Float, fill: Color? = null) {
    val middle = radius - stroke.width / 2f
    if (fill != null) drawCircle(fill, middle, centre)
    drawCircle(color, middle, centre, style = stroke)
}

/** One breath: the echo goes out over [OUT_SHARE] of it, and the rest is still. */
private const val BREATH_MS = 2_400
private const val OUT_SHARE = 0.8f

/** How far the echo widens past the ring, as a share of its radius, and how strong it leaves. */
private const val GROWTH = 1.6f
private const val ECHO_ALPHA = 0.9f
