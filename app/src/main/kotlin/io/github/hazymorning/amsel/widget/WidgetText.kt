package io.github.hazymorning.amsel.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import androidx.annotation.DrawableRes
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.components.inkOf
import io.github.hazymorning.amsel.ui.components.weighted

/** A widget's three kinds of words, as res/layout/widget_words.xml sets them: size and line in sp. */
internal enum class WidgetLine(val size: Float, val weight: Int, val line: Float) {
    Speaker(13f, 600, 16f),
    Title(16f, 700, 20f),
    Subtitle(13f, 400, 16f),
}

/** A glyph's ink in its square of [side] dp, and how far its middle stands off the square's. */
internal data class GlyphPlace(val ink: Ink, val dx: Float, val dy: Float)

/**
 * The phone's type and Amsel's glyphs as a widget sets them, in dp: where ink starts, so the widget
 * lines up by its ink as Amsel's screens do. The words are in the phone's own sans-serif (the widget's
 * layouts name it), which this process measures as the launcher draws it, at the phone's text size.
 */
internal class WidgetText(private val context: Context) {
    private val metrics get() = context.resources.displayMetrics

    /** [sp] at the phone's text size, which grows large text less than small. */
    fun dp(sp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics) / metrics.density

    val type: WidgetType
        get() = WidgetType(dp(WidgetLine.Speaker.line), dp(WidgetLine.Title.line), dp(WidgetLine.Subtitle.line))

    /** The speaker's glyph is as large as its name is set, as beside words in Amsel. */
    val speakerGlyph: Float get() = dp(WidgetLine.Speaker.size)

    private fun paint(line: WidgetLine) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, line.weight, false)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, line.size, metrics)
    }

    /** One line's height without the room a second line adds. */
    fun height(line: WidgetLine): Float = paint(line).fontMetricsInt.let { it.descent - it.ascent } / metrics.density

    /**
     * How far below a line's top its capitals start: the words line up there, whatever the title,
     * so the widget does not shift from song to song.
     */
    fun capTop(line: WidgetLine): Float {
        val paint = paint(line)
        val bounds = Rect().also { paint.getTextBounds("H", 0, 1, it) }
        return (-paint.fontMetricsInt.ascent + bounds.top) / metrics.density
    }

    /** [text]'s width set in [line], from its first letter's start to its last one's end. */
    fun width(text: String, line: WidgetLine): Float = paint(line).measureText(text) / metrics.density

    /** How far [text]'s first letter's ink stands in from its start; 0 for words set right to left. */
    fun sideBearing(text: String, line: WidgetLine): Float {
        if (text.isEmpty() || Character.getDirectionality(text.codePointAt(0)).isRightToLeft()) return 0f
        val end = text.offsetByCodePoints(0, 1)
        val bounds = Rect().also { paint(line).getTextBounds(text, 0, end, it) }
        return bounds.left / metrics.density
    }

    /** Where the words' ink starts below their top: the speaker's glyph, or its name's capitals. */
    fun wordsTop(words: WidgetWords): Float {
        if (!words.speaker) return capTop(WidgetLine.Title)
        val glyph = speakerGlyph
        val name = height(WidgetLine.Speaker)
        val row = maxOf(glyph, name)
        val ink = inkOf(weighted(SPEAKER, glyph.dp))
        val glyphTop = (row - glyph) / 2 + glyph * ink.top / GRID
        return minOf(glyphTop, (row - name) / 2 + capTop(WidgetLine.Speaker))
    }

    /** From the words' top to their one line's baseline, the title's, in a row or a bar. */
    fun wordsBaseline(words: WidgetWords): Float {
        val title = -paintMetrics(WidgetLine.Title).ascent / metrics.density
        if (!words.speaker) return title
        return maxOf(speakerGlyph, height(WidgetLine.Speaker)) + WidgetSizes.SPEAKER_TO_TITLE + title
    }

    private fun paintMetrics(line: WidgetLine) = paint(line).fontMetricsInt

    /** The cut of [glyph] Amsel draws [side] dp square, its line 1.5 dp at any size. */
    @DrawableRes
    fun cut(@DrawableRes glyph: Int, side: Float): Int = weighted(glyph, side.dp)

    /** [glyph] drawn [side] dp square, in the cut Amsel draws at that size. */
    fun glyph(@DrawableRes glyph: Int, side: Float): GlyphPlace {
        val ink = inkOf(weighted(glyph, side.dp))
        val scale = side / GRID
        return GlyphPlace(
            Ink((ink.right - ink.left) * scale, (ink.bottom - ink.top) * scale),
            dx = ((ink.left + ink.right) / 2 - GRID / 2) * scale,
            dy = ((ink.top + ink.bottom) / 2 - GRID / 2) * scale,
        )
    }

    /** How far the speaker's glyph's ink stands in from its square's start and end. */
    fun speakerSides(): Pair<Float, Float> {
        val side = speakerGlyph
        val ink = inkOf(weighted(SPEAKER, side.dp))
        return side * ink.left / GRID to side * (GRID - ink.right) / GRID
    }

    @get:DrawableRes
    val speakerCut: Int get() = weighted(SPEAKER, speakerGlyph.dp)

    private fun Byte.isRightToLeft() =
        this == Character.DIRECTIONALITY_RIGHT_TO_LEFT || this == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC

    private companion object {
        val SPEAKER = io.github.hazymorning.amsel.R.drawable.ic_speaker

        /** Phosphor's grid. */
        const val GRID = 256f
    }
}
