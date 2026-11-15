package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * What scrolls fades into the page over the dock's top edge, so the dock stands apart from it. The
 * one fade in the app: it parts two layers and decorates nothing. Eased at both ends, so neither
 * where it starts nor the dock's edge draws a line. It takes no touch.
 */
@Composable
fun DockFade(modifier: Modifier = Modifier) {
    val page = MaterialTheme.colorScheme.surface
    Spacer(
        modifier
            .fillMaxWidth()
            .height(FadeHeight)
            .drawWithCache {
                val brush = Brush.verticalGradient(
                    *FadeStops.map { (at, strength) ->
                        at to page.copy(alpha = strength)
                    }.toTypedArray(),
                )
                onDrawBehind { drawRect(brush) }
            },
    )
}

/** Smoothstep at five points: unseen where it starts, whole at the dock. */
private val FadeStops = listOf(0f to 0f, 0.25f to 0.156f, 0.5f to 0.5f, 0.75f to 0.844f, 1f to 1f)

internal val FadeHeight = 40.dp
