package io.github.hazymorning.amsel.widget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.withClip
import io.github.hazymorning.amsel.ui.theme.HAIRLINE_ALPHA
import io.github.hazymorning.amsel.ui.theme.Shapes
import kotlin.math.pow

/** The cover as the widget lays it down: blurred small, and whether ink reads on it better than paper. */
class WidgetCover(val bitmap: Bitmap, val light: Boolean)

/**
 * The widget's pictures, made on the phone: a launcher shows a widget's views as they are, so the
 * blur and the cover corners are drawn into the bitmaps themselves.
 */
object WidgetArt {
    /** The blurred cover's side; the launcher scales it up, which softens it further. */
    const val SIDE = 96

    /** The words stand in the upper part; the buttons have their own grounds. */
    private const val WORDS_SHARE = 0.6f

    fun cover(source: Bitmap): WidgetCover {
        val small = centerSquare(source).scale(SIDE, SIDE)
        val pixels = IntArray(SIDE * SIDE)
        small.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
        blur(pixels, SIDE, SIDE, BLUR_RADIUS)
        val blurred = Bitmap.createBitmap(pixels, SIDE, SIDE, Bitmap.Config.ARGB_8888)
        return WidgetCover(blurred, inkReads(pixels, SIDE, SIDE))
    }

    /** A Play … button's face: the item's cover [px] square, with the cover corners for [sideDp]. */
    fun thumb(source: Bitmap, px: Int, density: Float, sideDp: Float): Bitmap {
        val square = centerSquare(source)
        val out = createBitmap(px, px)
        val size = Size(px.toFloat(), px.toFloat())
        val outline = Shapes.cover(sideDp.dp).createOutline(size, LayoutDirection.Ltr, Density(density))
        val path = (outline as? Outline.Generic)?.path?.asAndroidPath() ?: return out
        val scale = px.toFloat() / square.width
        val shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(scale, scale) })
        }
        val canvas = Canvas(out)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader })
        // Every cover carries a hairline just inside its edge.
        val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = Color.argb((HAIRLINE_ALPHA * 255).toInt(), 0x19, 0x1A, 0x17)
        }
        canvas.withClip(path) { drawPath(path, hairline) }
        return out
    }

    private fun centerSquare(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        if (source.width == source.height) return source
        return Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    }

    /** Three box passes each way: close to a gaussian, and cheap at this size. */
    private fun blur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        val scratch = IntArray(pixels.size)
        repeat(BLUR_PASSES) {
            pass(pixels, scratch, width, height, radius, horizontal = true)
            pass(scratch, pixels, width, height, radius, horizontal = false)
        }
    }

    private fun pass(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val window = 2 * radius + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val c = i.coerceIn(0, length - 1)
                return if (horizontal) src[line * width + c] else src[c * width + line]
            }
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val p = at(i)
                r += p shr 16 and 0xFF
                g += p shr 8 and 0xFF
                b += p and 0xFF
            }
            for (i in 0 until length) {
                val out = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                if (horizontal) dst[line * width + i] = out else dst[i * width + line] = out
                val add = at(i + radius + 1)
                val drop = at(i - radius)
                r += (add shr 16 and 0xFF) - (drop shr 16 and 0xFF)
                g += (add shr 8 and 0xFF) - (drop shr 8 and 0xFF)
                b += (add and 0xFF) - (drop and 0xFF)
            }
        }
    }

    /**
     * Whether ink over the light veil reads better than paper over the dark one, on the part the
     * words stand on: the contrast of each against the cover as its veil leaves it.
     */
    internal fun inkReads(pixels: IntArray, width: Int, height: Int): Boolean {
        val rows = (height * WORDS_SHARE).toInt().coerceAtLeast(1)
        var underInk = 0.0
        var underPaper = 0.0
        for (y in 0 until rows) {
            val depth = y.toFloat() / height
            val light = veil(depth, LIGHT_VEIL)
            val dark = veil(depth, DARK_VEIL)
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                underInk += luminance(p, PAPER, light)
                underPaper += luminance(p, BLACK, dark)
            }
        }
        val count = rows * width
        val ink = contrast(underInk / count, luminance(INK, INK, 0f))
        val paper = contrast(underPaper / count, luminance(PAPER, PAPER, 0f))
        return ink >= paper
    }

    /** The veil's strength [depth] down the widget: its gradient's top, middle and foot. */
    private fun veil(depth: Float, stops: FloatArray): Float = if (depth < 0.5f) {
        stops[0] + (stops[1] - stops[0]) * depth * 2
    } else {
        stops[1] + (stops[2] - stops[1]) * (depth - 0.5f) * 2
    }

    /** Relative luminance of [pixel] under [veil] at [alpha], blended as Android blends. */
    private fun luminance(pixel: Int, veil: Int, alpha: Float): Double {
        fun channel(shift: Int): Double {
            val c = (pixel shr shift and 0xFF) * (1 - alpha) + (veil shr shift and 0xFF) * alpha
            val s = c / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(a: Double, b: Double): Double = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)

    private const val BLUR_RADIUS = 5
    private const val BLUR_PASSES = 3
    private const val INK = 0xFF191A17.toInt()
    private const val PAPER = 0xFFF2F1EC.toInt()
    private const val BLACK = 0xFF0A0A09.toInt()

    /** The day's veils (res/values/widget.xml): top, middle, foot. */
    private val LIGHT_VEIL = floatArrayOf(0.30f, 0.38f, 0.52f)
    private val DARK_VEIL = floatArrayOf(0.22f, 0.30f, 0.46f)
}
