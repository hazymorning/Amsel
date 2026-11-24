package io.github.hazymorning.amsel.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import androidx.annotation.DrawableRes
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.components.glyphBoundsOf
import io.github.hazymorning.amsel.ui.components.weighted

/** Text size and line height in sp, and weight; keep in step with res/layout/widget_words.xml. */
internal enum class WidgetLine(val size: Float, val weight: Int, val line: Float) {
    Speaker(13f, 400, 16f),
    Title(16f, 700, 20f),
    Subtitle(13f, 400, 16f),
}

/** A glyph's visible bounds in dp. */
internal data class WidgetGlyphBounds(val width: Float, val height: Float)

/** [dx], [dy]: the offset in dp of the visible bounds' centre from the drawable's centre. */
internal data class GlyphPosition(val ink: WidgetGlyphBounds, val dx: Float, val dy: Float)

/** Measures in this process what the launcher draws: the layouts use the system sans-serif, so the metrics match. */
internal class WidgetTextMetrics(private val context: Context, val size: WordSize = WordSize.Regular) {
    private val metrics get() = context.resources.displayMetrics
    private val scale get() = size.scale

    fun sized(size: WordSize) = if (size == this.size) this else WidgetTextMetrics(context, size)

    /** Through applyDimension, which applies Android 14's non-linear font scaling. */
    fun dp(sp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics) / metrics.density

    val type: WidgetLineHeights
        get() = WidgetLineHeights(dp(WidgetLine.Speaker.line), dp(WidgetLine.Title.line), dp(WidgetLine.Subtitle.line))
            .scaled(scale)

    /** The speaker glyph's side: widget_words.xml sizes it in sp, as the name. */
    val speakerGlyph: Float get() = dp(WidgetLine.Speaker.size * scale)

    private fun paint(line: WidgetLine) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, line.weight, false)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, line.size * scale, metrics)
    }

    /** Ascent to descent of one line, without line spacing. */
    fun height(line: WidgetLine): Float = paint(line).fontMetricsInt.let { it.descent - it.ascent } / metrics.density

    /** Measured on "H", not the text itself, so the alignment does not shift between titles. */
    fun capTop(line: WidgetLine): Float {
        val paint = paint(line)
        val bounds = Rect().also { paint.getTextBounds("H", 0, 1, it) }
        return (-paint.fontMetricsInt.ascent + bounds.top) / metrics.density
    }

    fun width(text: String, line: WidgetLine): Float = paint(line).measureText(text) / metrics.density

    /** The first glyph's left side bearing in dp; 0 for right-to-left text. */
    fun sideBearing(text: String, line: WidgetLine): Float {
        if (text.isEmpty() || Character.getDirectionality(text.codePointAt(0)).isRightToLeft()) return 0f
        val end = text.offsetByCodePoints(0, 1)
        val bounds = Rect().also { paint(line).getTextBounds(text, 0, end, it) }
        return bounds.left / metrics.density
    }

    fun endBearing(text: String, line: WidgetLine): Float {
        if (text.isEmpty()) return 0f
        val start = text.offsetByCodePoints(text.length, -1)
        val paint = paint(line)
        val bounds = Rect().also { paint.getTextBounds(text, start, text.length, it) }
        return (paint.measureText(text, start, text.length) - bounds.right) / metrics.density
    }

    /** The visible top below the box's top: the higher of the speaker glyph's and the name's, else the title's cap top. */
    fun wordsTop(words: WidgetWords): Float {
        if (!words.speaker) return capTop(WidgetLine.Title)
        val glyph = speakerGlyph
        val name = height(WidgetLine.Speaker)
        val row = maxOf(glyph, name)
        val ink = glyphBoundsOf(weighted(SPEAKER, glyph.dp))
        val glyphTop = (row - glyph) / 2 + glyph * ink.top / GRID
        return minOf(glyphTop, (row - name) / 2 + capTop(WidgetLine.Speaker))
    }

    private fun paintMetrics(line: WidgetLine) = paint(line).fontMetricsInt

    /** (box height, last baseline from its top) in dp, stacked as the layout does; [below]: lines under the title. */
    fun wordsStack(speaker: Boolean, titleLines: Int, below: Int): Pair<Float, Float> {
        var y = if (speaker) maxOf(speakerGlyph, height(WidgetLine.Speaker)) + size.speakerToTitle else 0f
        val more = (titleLines - 1).coerceAtLeast(0) * dp(WidgetLine.Title.line * scale)
        var baseline = y + more - paintMetrics(WidgetLine.Title).ascent / metrics.density
        y += height(WidgetLine.Title) + more
        repeat(below) {
            y += WidgetSizes.TITLE_TO_SUBTITLE
            baseline = y - paintMetrics(WidgetLine.Subtitle).ascent / metrics.density
            y += height(WidgetLine.Subtitle)
        }
        return y to baseline
    }

    fun lines(text: String, line: WidgetLine, width: Float, max: Int): Int {
        if (max <= 1 || width <= 0f) return 1
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, TextPaint(paint(line)), (width * metrics.density).toInt())
            .setIncludePad(false)
            .setMaxLines(max)
            .build()
        return layout.lineCount.coerceIn(1, max)
    }

    /** The weight of [glyph] whose stroke at [side] dp is nearest 1.5 dp. */
    @DrawableRes
    fun cut(@DrawableRes glyph: Int, side: Float): Int = weighted(glyph, side.dp)

    fun glyph(@DrawableRes glyph: Int, side: Float): GlyphPosition {
        val ink = glyphBoundsOf(weighted(glyph, side.dp))
        val scale = side / GRID
        return GlyphPosition(
            WidgetGlyphBounds((ink.right - ink.left) * scale, (ink.bottom - ink.top) * scale),
            dx = ((ink.left + ink.right) / 2 - GRID / 2) * scale,
            dy = ((ink.top + ink.bottom) / 2 - GRID / 2) * scale,
        )
    }

    fun speakerSides(): Pair<Float, Float> = sides(SPEAKER)

    fun levelSides(): Pair<Float, Float> = sides(VOLUME)

    private fun sides(@DrawableRes glyph: Int): Pair<Float, Float> {
        val side = speakerGlyph
        val ink = glyphBoundsOf(weighted(glyph, side.dp))
        return side * ink.left / GRID to side * (GRID - ink.right) / GRID
    }

    @get:DrawableRes
    val speakerCut: Int get() = weighted(SPEAKER, speakerGlyph.dp)

    @get:DrawableRes
    val levelCut: Int get() = weighted(VOLUME, speakerGlyph.dp)

    private fun Byte.isRightToLeft() =
        this == Character.DIRECTIONALITY_RIGHT_TO_LEFT || this == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC

    private companion object {
        val SPEAKER = io.github.hazymorning.amsel.R.drawable.ic_speaker
        val VOLUME = io.github.hazymorning.amsel.R.drawable.ic_volume

        /** The Phosphor icons' viewport. */
        const val GRID = 256f
    }
}
