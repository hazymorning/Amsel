package io.github.hazymorning.amsel.widget

import androidx.compose.runtime.Immutable
import kotlin.math.ceil
import kotlin.math.floor

enum class WidgetForm {
    Tile,

    Pad,

    Line,

    Card,

    Bar,

    Player,

    Poster,
}

/** In dp at the phone's font scale, which Android 14 applies non-linearly: large text grows less. */
@Immutable
data class WidgetLineHeights(val speaker: Float = 16f, val title: Float = 20f, val subtitle: Float = 16f) {
    fun scaled(factor: Float) = WidgetLineHeights(speaker * factor, title * factor, subtitle * factor)
}

/** [scale] matches the text sizes of widget_words.xml, widget_words_small.xml and widget_words_large.xml. */
enum class WordSize(val scale: Float, val speakerToTitle: Float) {
    Small(0.875f, 4f),
    Regular(1f, 10f),
    Large(1.2f, 10f),
}

@Immutable
data class WidgetWords(val speaker: Boolean, val titleLines: Int, val subtitle: Boolean, val time: Boolean = false) {
    fun needs(type: WidgetLineHeights, size: WordSize = WordSize.Regular): Float {
        val set = type.scaled(size.scale)
        return (if (speaker) set.speaker + size.speakerToTitle else 0f) +
            set.title * titleLines +
            (if (subtitle) WidgetSizes.TITLE_TO_SUBTITLE + set.subtitle else 0f) +
            if (time) WidgetSizes.TITLE_TO_SUBTITLE + set.subtitle else 0f
    }

    companion object {
        val None = WidgetWords(speaker = false, titleLines = 0, subtitle = false)
    }
}

/** Counts and sizes only: positions are left to the layouts, which hold at whatever size the launcher draws. */
@Immutable
data class WidgetFit(
    val form: WidgetForm,
    val width: Float,
    val height: Float,
    /** How many buttons fit; [standing] picks which. */
    val buttons: Int,
    /** The cover's side in dp in a bar, a player or a poster; 0 elsewhere. */
    val art: Float = 0f,
    val words: WidgetWords = WidgetWords.None,
    val size: WordSize = WordSize.Regular,
    /** Buttons per row; the last row may hold fewer. */
    val columns: Int = buttons,
    /** A pad's cell height in dp. */
    val cell: Float = WidgetSizes.CELL_HEIGHT,
)

/** In dp; keep in step with res/values/widget.xml. */
object WidgetSizes {
    /** Android's own widget padding (system_app_widget_internal_padding). */
    const val PADDING = 16f

    /** Between cells: each cell is inset half of it in its touch target, so neighbouring targets meet. */
    const val GAP = 4f

    /** How near the edge bare buttons' targets reach, as they draw no cell. */
    const val REACH = 4f

    /** The minimum touch target width. */
    const val CELL_MIN = 48f

    const val CELL_HEIGHT = 56f

    /** A bar's button target; its cell is 48. */
    const val BAR_CELL = 52f

    const val WORDS_TO_CELLS = 8f

    const val ART_TO_WORDS = 12f

    const val ART_SHARE = 0.5f

    /** A bar's minimum text width at the default font scale. */
    const val WORDS_MIN = 80f

    const val GLYPH = 24f

    const val TITLE_TO_SUBTITLE = 4f

    /**
     * How far the text view stands outside its box (widget_words_lead_out): padding cannot be
     * negative, so each line is padded back in by LEAD less its first glyph's side bearing.
     */
    const val LEAD = 6f

    /** Two cells by Android's sizing formula (70 × n − 30 dp); below it a widget is one cell wide or high. */
    const val TWO_CELLS = 110f

    /** From this width a cover fits beside the text (Bar, Player). */
    const val WIDE = 300f
}

/** [words]: whether the text shows at all; [bare]: buttons without cells, which reach into the frame. */
fun widgetFit(
    width: Float,
    height: Float,
    buttons: Int,
    type: WidgetLineHeights = WidgetLineHeights(),
    time: Boolean = false,
    words: Boolean = true,
    bare: Boolean = false,
): WidgetFit {
    val s = WidgetSizes
    val oneRow = height < s.TWO_CELLS
    val inner = width - 2 * s.PADDING
    val innerHeight = height - 2 * s.PADDING
    val across = cells(span(width, bare), buttons)
    val rows = rows(innerHeight, FLOOR.needs(type), across, buttons)
    val shown = minOf(buttons, across * rows)
    val columns = if (rows == 0) 0 else ceil(shown.toFloat() / rows).toInt()
    val form = when {
        width < s.TWO_CELLS && oneRow -> WidgetForm.Tile

        // One row narrower than a bar leaves no space for text beside buttons.
        buttons > 0 && (!words || (oneRow && width < s.WIDE)) -> WidgetForm.Pad

        oneRow -> if (width >= s.WIDE) WidgetForm.Bar else WidgetForm.Line

        width >= s.WIDE -> WidgetForm.Player

        width >= s.TWO_CELLS && innerHeight >= inner + s.ART_TO_WORDS +
            SPEAKER_AND_TITLE.needs(type) + under(rows) -> WidgetForm.Poster

        else -> WidgetForm.Card
    }
    return when (form) {
        WidgetForm.Tile -> WidgetFit(form, width, height, buttons = minOf(1, buttons))

        WidgetForm.Pad -> pad(width, height, buttons, bare)

        WidgetForm.Line -> said(WidgetFit(form, width, height, 0), innerHeight, type, alone = true, strip = true)

        WidgetForm.Card -> {
            val fit = WidgetFit(form, width, height, shown, columns = columns)
            said(fit, innerHeight - under(rows), type, shown == 0, time)
        }

        WidgetForm.Bar -> {
            val art = innerHeight
            // WORDS_MIN grows with the font scale, so at large text a button gives way before the text.
            val least = s.WORDS_MIN * type.title / WidgetLineHeights().title
            val room = inner - art - s.ART_TO_WORDS - least - s.WORDS_TO_CELLS
            val shown = floor((room + s.GAP + EPSILON) / s.BAR_CELL).toInt().coerceIn(0, buttons)
            said(WidgetFit(form, width, height, shown, art), innerHeight, type, shown == 0, strip = true)
        }

        WidgetForm.Poster -> {
            val room = innerHeight - inner - s.ART_TO_WORDS - under(rows)
            val fit = WidgetFit(form, width, height, shown, art = inner, columns = columns)
            said(fit, room, type, alone = false, time = time)
        }

        WidgetForm.Player -> {
            val art = minOf(innerHeight - under(rows), inner * s.ART_SHARE)
            said(WidgetFit(form, width, height, shown, art, columns = columns), art, type, shown == 0, time)
        }
    }
}

/** One row, and more while the text keeps [floor] dp of [room]; none when [across] is 0. */
private fun rows(room: Float, floor: Float, across: Int, buttons: Int): Int {
    if (across == 0) return 0
    val wanted = ceil(buttons.toFloat() / across).toInt()
    var rows = 1
    while (rows < wanted && room - under(rows + 1) >= floor - EPSILON) rows++
    return rows
}

internal fun buttonRows(fit: WidgetFit): Int =
    if (fit.columns == 0) 0 else ceil(fit.buttons.toFloat() / fit.columns).toInt()

internal fun rowsHeight(rows: Int): Float =
    if (rows == 0) 0f else rows * WidgetSizes.CELL_HEIGHT + (rows - 1) * WidgetSizes.GAP

/** A cell's (width, height) in dp as the layouts lay it out, for Play … covers; null without cells. */
internal fun cellSize(fit: WidgetFit, bare: Boolean): Pair<Float, Float>? {
    val s = WidgetSizes
    if (fit.buttons == 0 || fit.columns == 0) return null
    return when (fit.form) {
        WidgetForm.Bar -> s.BAR_CELL - s.GAP to minOf(fit.height - 2 * s.PADDING, s.CELL_HEIGHT)

        WidgetForm.Pad -> span(fit.width, bare) / fit.columns - s.GAP to fit.cell

        WidgetForm.Card, WidgetForm.Player, WidgetForm.Poster ->
            span(fit.width, bare) / fit.columns - s.GAP to s.CELL_HEIGHT

        WidgetForm.Tile, WidgetForm.Line -> null
    }
}

internal fun span(width: Float, bare: Boolean): Float =
    if (bare) width - 2 * WidgetSizes.REACH else width - 2 * WidgetSizes.PADDING + WidgetSizes.GAP

/** A touch target high where the height allows; in a low widget the targets reach to REACH from the edge. */
internal fun target(cell: Float, height: Float): Float =
    maxOf(cell, minOf(WidgetSizes.CELL_MIN, height - 2 * WidgetSizes.REACH))

internal fun cells(span: Float, wanted: Int): Int =
    floor((span + EPSILON) / WidgetSizes.CELL_MIN).toInt().coerceIn(0, wanted)

/** As few rows as hold the buttons, filled evenly; cells about square, at least CELL_HEIGHT where the height allows. */
private fun pad(width: Float, height: Float, buttons: Int, bare: Boolean): WidgetFit {
    val s = WidgetSizes
    val row = span(width, bare)
    val across = cells(row, buttons).coerceAtLeast(1)
    val down = floor((height - 2 * s.PADDING + s.GAP + EPSILON) / (s.CELL_MIN + s.GAP)).toInt().coerceAtLeast(1)
    val shown = minOf(buttons, across * down)
    val rows = ceil(shown.toFloat() / across).toInt()
    val columns = ceil(shown.toFloat() / rows).toInt()
    val wide = row / columns - s.GAP
    val most = (height - 2 * s.PADDING - (rows - 1) * s.GAP) / rows
    val cell = minOf(most, maxOf(s.CELL_HEIGHT, wide))
    return WidgetFit(WidgetForm.Pad, width, height, shown, columns = columns, cell = cell)
}

private fun under(rows: Int): Float = if (rows == 0) 0f else rowsHeight(rows) + WidgetSizes.WORDS_TO_CELLS

private val SPEAKER_AND_TITLE = WidgetWords(speaker = true, titleLines = 1, subtitle = false)

/** A second row of buttons only where the text keeps this. */
private val FLOOR = WidgetWords(speaker = true, titleLines = 1, subtitle = true)
private val TITLE = WidgetWords(speaker = false, titleLines = 1, subtitle = false)

/**
 * The most text [room] dp hold, in the order of `lines`; Large type only where every line fits. Before
 * the speaker's line goes, it and the title try Small type.
 */
private fun said(
    fit: WidgetFit,
    room: Float,
    type: WidgetLineHeights,
    alone: Boolean,
    time: Boolean = false,
    strip: Boolean = false,
): WidgetFit {
    // Centred text is placed by its visible extent (cap top to last baseline); for the two Small lines
    // that is more than LEAD less than their box, which may overflow the frame by LEAD / 2 each way.
    val centred = fit.form == WidgetForm.Line || fit.form == WidgetForm.Bar || fit.form == WidgetForm.Player
    fun fits(words: WidgetWords, size: WordSize) = words.needs(type, size) <= room + EPSILON
    val lines = listOfNotNull(
        WidgetWords(speaker = true, titleLines = 3, subtitle = true, time = true).takeIf { alone && time && !strip },
        WidgetWords(speaker = true, titleLines = 3, subtitle = true).takeIf { alone && !strip },
        WidgetWords(speaker = true, titleLines = 2, subtitle = true, time = true).takeIf { time && !strip },
        WidgetWords(speaker = true, titleLines = 2, subtitle = true).takeIf { !strip },
        WidgetWords(speaker = true, titleLines = 1, subtitle = true),
        SPEAKER_AND_TITLE,
    )
    val large = lines.firstOrNull { fits(it, WordSize.Large) }?.takeIf { !strip && it.whole(time) }
    if (large != null) return fit.copy(words = large, size = WordSize.Large)
    val regular = lines.firstOrNull { fits(it, WordSize.Regular) }
    if (regular != null) return fit.copy(words = regular, size = WordSize.Regular)
    if (SPEAKER_AND_TITLE.needs(type, WordSize.Small) <= room + (if (centred) WidgetSizes.LEAD else 0f) + EPSILON) {
        return fit.copy(words = SPEAKER_AND_TITLE, size = WordSize.Small)
    }
    val (words, size) = listOf(TITLE to WordSize.Regular, TITLE to WordSize.Small)
        .firstOrNull { (words, size) -> fits(words, size) } ?: (WidgetWords.None to WordSize.Regular)
    return fit.copy(words = words, size = size)
}

private fun WidgetWords.whole(time: Boolean) = speaker && titleLines >= 2 && subtitle && (this.time || !time)

private const val EPSILON = 0.01f
