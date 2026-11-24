package io.github.hazymorning.amsel.ui.theme

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.core.graphics.withTranslation

/** One shade as CSS writes it: `0 drop blur spread color`. */
class ShadowLayer(val drop: Dp, val blur: Dp, val spread: Dp, val color: Color)

/**
 * How far a large cover and a play button stand off the page (docs/DESIGN.md, "Shadows"): a cover
 * on a warm shade that falls below it, with a faint contact line; a play button on a short one.
 */
enum class Elevation(vararg val shades: ShadowLayer) {
    Cover(
        ShadowLayer(drop = 1.dp, blur = 2.dp, spread = 0.dp, color = Umber.copy(alpha = 0.05f)),
        ShadowLayer(drop = 24.dp, blur = 48.dp, spread = (-24).dp, color = Umber.copy(alpha = 0.38f)),
    ),
    Button(ShadowLayer(drop = 6.dp, blur = 16.dp, spread = (-8).dp, color = Color(0xFF1E1D19).copy(alpha = 0.4f))),
    ;

    /** How far past the bounds the shades show: a blur fades out over one and a half times its length. */
    val overflow: Dp get() = shades.maxOf { it.spread + it.drop + it.blur * BLUR_OVERFLOW }
}

/** The cover shade's warm brown, from the page's paper rather than black. */
private val Umber = Color(0xFF3C3018)

/**
 * Draws the shades behind what follows. A blurred path, not Compose's drop shadow: that one stops a
 * blur's length out, where its shade still shows as an edge.
 */
fun Modifier.elevationShadow(shape: Shape, elevation: Elevation): Modifier = drawWithCache {
    val layers = elevation.shades.map { shade ->
        val grow = shade.spread.toPx()
        val outline = shape.createOutline(Size(size.width + 2 * grow, size.height + 2 * grow), layoutDirection, this)
        val path = Path().apply { addOutline(outline) }.asAndroidPath()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = shade.color.toArgb()
            // CSS blurs by a Gaussian of half its radius; Android's radius r blurs by 0.57735 r + 0.5.
            val sigma = shade.blur.toPx() / 2
            if (sigma > 0.5f) maskFilter = BlurMaskFilter((sigma - 0.5f) / BLUR_SIGMA, BlurMaskFilter.Blur.NORMAL)
        }
        Triple(path, paint, -grow to shade.drop.toPx() - grow)
    }
    onDrawBehind {
        drawIntoCanvas {
            for ((path, paint, offset) in layers) {
                it.nativeCanvas.withTranslation(offset.first, offset.second) { drawPath(path, paint) }
            }
        }
    }
}

/**
 * Room for the shades of an [elevation] in [layers] that move or fade what follows: they are set
 * [Elevation.overflow] larger on every side, and what follows keeps its size and place. A layer that fades
 * is drawn off screen first and cut at its own bounds, which without this are the cover's, so the
 * shade went whenever the cover faded.
 */
fun Modifier.extendForShadow(elevation: Elevation, layers: Modifier): Modifier = layout { measurable, constraints ->
    val overflow = elevation.overflow.roundToPx()
    val placeable = measurable.measure(constraints.offset(2 * overflow, 2 * overflow))
    layout(placeable.width - 2 * overflow, placeable.height - 2 * overflow) { placeable.place(-overflow, -overflow) }
}.then(layers).padding(elevation.overflow)

private const val BLUR_SIGMA = 0.57735f
private const val BLUR_OVERFLOW = 1.5f
