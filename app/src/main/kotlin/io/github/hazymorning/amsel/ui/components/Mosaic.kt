package io.github.hazymorning.amsel.ui.components

import android.graphics.Bitmap
import androidx.core.graphics.scale
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Crops the server's six-by-six smart playlist collage to its top-left corner, as few covers across as
 * stay sharp at [px] (two on a tile, three on its page), never enlarged; any other picture stays
 * whole. See docs/API-NOTES.md, "Images".
 */
internal class MosaicCrop(private val px: Int) : Transformation() {
    override val cacheKey: String = "mosaic-$px"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val part = if (isGrid(input)) {
            val tile = input.width / TILES.toFloat()
            val across = (FEWEST..TILES).firstOrNull { it * tile >= px } ?: TILES
            val side = (across * tile).roundToInt().coerceAtMost(minOf(input.width, input.height))
            Bitmap.createBitmap(input, 0, 0, side, side)
        } else {
            input
        }
        return if (part.width > px) part.scale(px, px * part.height / part.width) else part
    }

    override fun equals(other: Any?): Boolean = other is MosaicCrop && other.px == px

    override fun hashCode(): Int = px
}

/** A six-by-six grid: luma changes across its seams far more than across the middles of its tiles. */
private fun isGrid(bitmap: Bitmap): Boolean {
    val w = bitmap.width
    val h = bitmap.height
    if (w < MIN_SIDE || h < MIN_SIDE || abs(w - h) > w / SQUARE_SLACK) return false
    val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
    fun luma(x: Int, y: Int): Int {
        val c = pixels[y * w + x]
        return ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10
    }
    fun across(x: Int): Double = (0 until h step STEP).sumOf { y -> abs(luma(x - SPAN, y) - luma(x + SPAN, y)) }
        .toDouble() / (h / STEP)
    fun down(y: Int): Double = (0 until w step STEP).sumOf { x -> abs(luma(x, y - SPAN) - luma(x, y + SPAN)) }
        .toDouble() / (w / STEP)
    fun seams(length: Int, diff: (Int) -> Double): Boolean {
        val seam = (1 until TILES).sumOf { diff((it * length / TILES.toFloat()).roundToInt()) }
        val middle = (1 until TILES).sumOf { diff(((it - 0.5f) * length / TILES).roundToInt()) }
        return seam > MIN_SEAM * (TILES - 1) && seam > middle * CONTRAST
    }
    return seams(w, ::across) && seams(h, ::down)
}

private const val TILES = 6

private const val FEWEST = 2
private const val MIN_SIDE = 60
private const val SQUARE_SLACK = 20
private const val STEP = 2
private const val SPAN = 2
private const val MIN_SEAM = 10.0
private const val CONTRAST = 3.0

/** The crop keeps at least a third of the side (two of six covers), so a collage loads this much larger. */
internal const val MOSAIC_SCALE = 3
