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

/** Whether the speaker in use is playing, provided at the app root; the play bars move only then. */
val LocalMusicPlays = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

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
            // Offset phases: from the first frame the bars differ in height and never all sink like an ellipsis.
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

private fun swell(phase: Float): Float = 0.5f - 0.5f * cos(2f * PI.toFloat() * phase)

private val BarWidth = 2.dp
private val BarGap = 1.5.dp
private val BarsWidth = BarWidth * 3 + BarGap * 2

/** The cap height of a list's second line, so the bars span its baseline to its cap top. */
private val BarsHeight = 10.dp

/** The lowest share of its height a moving bar sinks to, so it never shrinks to a dot. */
private const val LOW = 0.35f

/** Cycle lengths in ms, a little apart, so the bars never fall into step. */
private val CYCLES = listOf(1_640, 2_120, 1_880)

private val OFFSETS = listOf(0.6f, 0f, 0.3f)

private val RESTING = listOf(0.5f, 1f, 0.7f)
