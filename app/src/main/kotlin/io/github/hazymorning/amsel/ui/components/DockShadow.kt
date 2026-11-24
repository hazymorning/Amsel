package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.theme.isNight

@Composable
fun DockShadow(modifier: Modifier = Modifier) {
    val strength = if (MaterialTheme.colorScheme.isNight) NIGHT_SHADOW else DAY_SHADOW
    Spacer(
        modifier
            .fillMaxWidth()
            .height(ShadowHeight)
            .drawWithCache {
                val stops = ShadowStops.map { (at, share) -> at to Color.Black.copy(alpha = strength * share) }
                val brush = Brush.verticalGradient(*stops.toTypedArray())
                onDrawBehind { drawRect(brush) }
            },
    )
}

/** Alpha share = position squared: a falloff that reads as depth, not as a line. */
private val ShadowStops = listOf(0f to 0f, 0.4f to 0.16f, 0.7f to 0.49f, 0.9f to 0.81f, 1f to 1f)

internal val ShadowHeight = 12.dp

private const val DAY_SHADOW = 0.10f
private const val NIGHT_SHADOW = 0.40f
