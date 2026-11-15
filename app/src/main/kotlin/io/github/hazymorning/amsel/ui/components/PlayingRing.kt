package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.hazymorning.amsel.ui.theme.Sizes

/** The thin moss ring that marks what is playing, beside it in a list. It holds still. */
@Composable
fun PlayingRing(modifier: Modifier = Modifier) {
    val moss = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.size(Sizes.Ring)) {
        drawRing(moss, Stroke(Sizes.RingStroke.toPx()), center, this.size.minDimension / 2f)
    }
}

/** A ring of [stroke] whose outer edge has [radius], around [centre]; [fill] closes its middle. */
fun DrawScope.drawRing(color: Color, stroke: Stroke, centre: Offset, radius: Float, fill: Color? = null) {
    val middle = radius - stroke.width / 2f
    if (fill != null) drawCircle(fill, middle, centre)
    drawCircle(color, middle, centre, style = stroke)
}
