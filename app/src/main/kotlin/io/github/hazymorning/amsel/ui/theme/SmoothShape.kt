package io.github.hazymorning.amsel.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** How far a corner's curve eases into its straight edges: 0 is a plain arc, 1 the longest ease. */
private const val CORNER_SMOOTHING = 0.6f

/**
 * Continuous corners: each corner keeps a shorter circular arc in its middle and eases into its
 * edges along curves of growing radius, so the corner reads as grown rather than machined. A corner
 * too large for the shape gives up its easing first, so a fully round end stays a plain half circle.
 */
@Immutable
class SmoothShape(
    private val topStart: Dp,
    private val topEnd: Dp,
    private val bottomEnd: Dp,
    private val bottomStart: Dp,
) : Shape {
    constructor(radius: Dp) : this(radius, radius, radius, radius)

    companion object {
        /** Rounded on top only: a sheet's or a layer's corners. */
        fun top(radius: Dp): SmoothShape = SmoothShape(radius, radius, 0.dp, 0.dp)
    }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val tl = with(density) { (if (rtl) topEnd else topStart).toPx() }
        val tr = with(density) { (if (rtl) topStart else topEnd).toPx() }
        val br = with(density) { (if (rtl) bottomStart else bottomEnd).toPx() }
        val bl = with(density) { (if (rtl) bottomEnd else bottomStart).toPx() }
        if (tl + tr + br + bl == 0f) return Outline.Rectangle(Rect(Offset.Zero, size))
        val budget = min(size.width, size.height) / 2f
        val path = Path()
        val w = size.width
        val h = size.height
        val topLeft = corner(tl, budget)
        val topRight = corner(tr, budget)
        val bottomRight = corner(br, budget)
        val bottomLeft = corner(bl, budget)
        path.moveTo(topLeft.p, 0f)
        topRight.draw(path, w, 0f, Quarter.TopRight)
        bottomRight.draw(path, w, h, Quarter.BottomRight)
        bottomLeft.draw(path, 0f, h, Quarter.BottomLeft)
        topLeft.draw(path, 0f, 0f, Quarter.TopLeft)
        path.close()
        return Outline.Generic(path)
    }

    override fun equals(other: Any?): Boolean = other is SmoothShape &&
        topStart == other.topStart &&
        topEnd == other.topEnd &&
        bottomEnd == other.bottomEnd &&
        bottomStart == other.bottomStart

    override fun hashCode(): Int =
        ((topStart.hashCode() * 31 + topEnd.hashCode()) * 31 + bottomEnd.hashCode()) * 31 + bottomStart.hashCode()

    private fun corner(radius: Float, budget: Float): Corner {
        val r = min(radius, budget)
        if (r <= 0f) return Corner.Square
        val s = min(CORNER_SMOOTHING, budget / r - 1f).coerceAtLeast(0f)
        val p = min((1f + s) * r, budget)
        val arc = 90f * (1f - s)
        val arcChord = sin(Math.toRadians(arc / 2.0)).toFloat() * r * sqrt(2f)
        val alpha = (90f - arc) / 2f
        val p3ToP4 = r * tan(Math.toRadians(alpha / 2.0)).toFloat()
        val beta = 45f * s
        val c = p3ToP4 * cos(Math.toRadians(beta.toDouble())).toFloat()
        val d = c * tan(Math.toRadians(beta.toDouble())).toFloat()
        val b = (p - arcChord - c - d) / 3f
        return Corner(r, p, 2f * b, b, c, d, arc)
    }

    /** Each corner's arc centre, and its directions along the incoming edge (u) and the outgoing one (v), clockwise. */
    private enum class Quarter(val centreAngle: Float, val ux: Float, val uy: Float, val vx: Float, val vy: Float) {
        TopRight(-45f, 1f, 0f, 0f, 1f),
        BottomRight(45f, 0f, 1f, -1f, 0f),
        BottomLeft(135f, -1f, 0f, 0f, -1f),
        TopLeft(225f, 0f, -1f, 1f, 0f),
    }

    /** One corner's curve: a cubic from the edge, a middle arc, a cubic onto the next edge. */
    private class Corner(
        val r: Float,
        val p: Float,
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float,
        val arc: Float,
    ) {
        /** Draws from the corner's start on the incoming edge to its end on the outgoing one, clockwise. */
        fun draw(path: Path, x: Float, y: Float, quarter: Quarter) {
            if (r <= 0f) {
                path.lineTo(x, y)
                return
            }
            val ux = quarter.ux
            val uy = quarter.uy
            val vx = quarter.vx
            val vy = quarter.vy
            val sx = x - ux * p
            val sy = y - uy * p
            path.lineTo(sx, sy)
            fun along(t: Float, n: Float) = Offset(sx + ux * t + vx * n, sy + uy * t + vy * n)
            val c1 = along(a, 0f)
            val c2 = along(a + b, 0f)
            val e1 = along(a + b + c, d)
            path.cubicTo(c1.x, c1.y, c2.x, c2.y, e1.x, e1.y)
            val centre = Offset(x - ux * r + vx * r, y - uy * r + vy * r)
            path.arcTo(
                Rect(centre, r),
                startAngleDegrees = quarter.centreAngle - arc / 2f,
                sweepAngleDegrees = arc,
                forceMoveTo = false,
            )
            val ex = x + vx * p
            val ey = y + vy * p
            fun back(t: Float, n: Float) = Offset(ex - vx * t - ux * n, ey - vy * t - uy * n)
            val c3 = back(a + b, 0f)
            val c4 = back(a, 0f)
            path.cubicTo(c3.x, c3.y, c4.x, c4.y, ex, ey)
        }

        companion object {
            val Square = Corner(0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
    }
}
