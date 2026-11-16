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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.theme.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.cos

/** Whether music plays on the speaker in use, from the app's root: a list's play bars move only then. */
val LocalMusicPlays = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/**
 * Three short moss bars that mark what is playing, beside it in a list, standing on the line's
 * baseline as high as its capitals. While the music plays ([moving]) each rises and falls at its
 * own pace inside the mark's own box, so nothing beside it ever moves; paused, or with reduced
 * motion, they stand still at three heights.
 */
@Composable
fun PlayBars(modifier: Modifier = Modifier, moving: Boolean = LocalMusicPlays.current.value) {
    val moss = MaterialTheme.colorScheme.tertiary
    val phases: List<State<Float>>? = if (moving && !LocalReducedMotion.current) {
        val bars = rememberInfiniteTransition(label = "play bars")
        CYCLES.mapIndexed { index, ms ->
            bars.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing)), label = "bar $index")
        }
    } else {
        null
    }
    Canvas(modifier.size(BarsWidth, BarsHeight)) {
        val width = BarWidth.toPx()
        val step = width + BarGap.toPx()
        RESTING.indices.forEach { index ->
            // Each bar starts at its own point of its swell, so from the first frame the three
            // stand at three heights and never move as one, never all low like an ellipsis.
            val level = phases?.let { LOW + (1f - LOW) * swell(it[index].value + OFFSETS[index]) } ?: RESTING[index]
            val height = size.height * level
            drawRoundRect(
                moss,
                topLeft = Offset(index * step, size.height - height),
                size = Size(width, height),
                cornerRadius = CornerRadius(width / 2),
            )
        }
    }
}

/** A smooth rise and fall over one cycle: 0 at its ends, 1 half way. */
private fun swell(phase: Float): Float = 0.5f - 0.5f * cos(2f * PI.toFloat() * phase)

private val BarWidth = 2.dp
private val BarGap = 1.5.dp
private val BarsWidth = BarWidth * 3 + BarGap * 2

/** As high as a list's second line's capitals, so the bars stand on its baseline and end at its caps. */
private val BarsHeight = 10.dp

/** How low a bar sinks while it moves, as a share of its height: still a bar, never a dot. */
private const val LOW = 0.35f

/** Each bar's rise and fall, a little apart, so the three never fall into step. */
private val CYCLES = listOf(1_640, 2_120, 1_880)

/** Where in its cycle each bar starts. */
private val OFFSETS = listOf(0.6f, 0f, 0.3f)

/** The heights the bars hold while the music rests. */
private val RESTING = listOf(0.5f, 1f, 0.7f)
