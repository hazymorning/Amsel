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
class Shade(val drop: Dp, val blur: Dp, val spread: Dp, val color: Color)

/**
 * How far a large cover and a play button stand off the page (docs/DESIGN.md, "Shadows"): a cover
 * on a warm shade that falls below it, with a faint contact line; a play button on a short one.
 */
enum class Lift(vararg val shades: Shade) {
    Cover(
        Shade(drop = 1.dp, blur = 2.dp, spread = 0.dp, color = Umber.copy(alpha = 0.05f)),
        Shade(drop = 24.dp, blur = 48.dp, spread = (-24).dp, color = Umber.copy(alpha = 0.38f)),
    ),
    Button(Shade(drop = 6.dp, blur = 16.dp, spread = (-8).dp, color = Color(0xFF1E1D19).copy(alpha = 0.4f))),
    ;

    /** How far past the bounds the shades show: a blur fades out over one and a half times its length. */
    val reach: Dp get() = shades.maxOf { it.spread + it.drop + it.blur * BLUR_REACH }
}

/** The cover shade's warm brown, from the page's paper rather than black. */
private val Umber = Color(0xFF3C3018)

/**
 * Draws the shades behind what follows. A blurred path, not Compose's drop shadow: that one stops a
 * blur's length out, where its shade still shows as an edge.
 */
fun Modifier.lift(shape: Shape, lift: Lift): Modifier = drawWithCache {
    val layers = lift.shades.map { shade ->
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
 * Room for the shades of a [lift] in [layers] that move or fade what follows: they are set
 * [Lift.reach] larger on every side, and what follows keeps its size and place. A layer that fades
 * is drawn off screen first and cut at its own bounds, which without this are the cover's, so the
 * shade went whenever the cover faded.
 */
fun Modifier.around(lift: Lift, layers: Modifier): Modifier = layout { measurable, constraints ->
    val reach = lift.reach.roundToPx()
    val placeable = measurable.measure(constraints.offset(2 * reach, 2 * reach))
    layout(placeable.width - 2 * reach, placeable.height - 2 * reach) { placeable.place(-reach, -reach) }
}.then(layers).padding(lift.reach)

private const val BLUR_SIGMA = 0.57735f
private const val BLUR_REACH = 1.5f
