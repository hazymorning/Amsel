package io.github.hazymorning.amsel.widget

import androidx.compose.runtime.Immutable
import kotlin.math.ceil
import kotlin.math.floor

/** What a widget is at its size. */
enum class WidgetForm {
    /** One cell: one button in the middle, or a cover. */
    Tile,

    /** Buttons alone, in rows in the frame's middle: one row high, or what plays left out. */
    Pad,

    /** One row without buttons: what plays. */
    Line,

    /** Two rows and more: what plays above or below a row of buttons. */
    Card,

    /** One row, four cells wide: the cover and what plays, the buttons before or after them. */
    Bar,

    /** Two rows and more, four cells wide: the cover beside what plays, above or below a row of buttons. */
    Player,

    /** Two cells wide and tall enough: the cover across, what plays under it, a row of buttons. */
    Poster,
}

/** The words' line heights in dp at the phone's text size, which grows large text less than small. */
@Immutable
data class WidgetType(val speaker: Float = 16f, val title: Float = 20f, val subtitle: Float = 16f) {
    /** The same type [factor] times as large. */
    fun scaled(factor: Float) = WidgetType(speaker * factor, title * factor, subtitle * factor)
}

/**
 * How large what plays is set, against res/layout/widget_words.xml: smaller, closer lines keep the
 * speaker over the title in a strip; larger where every line has room.
 */
enum class WordSize(val scale: Float, val speakerToTitle: Float) {
    Small(0.875f, 4f),
    Regular(1f, 10f),
    Large(1.2f, 10f),
}

/** How much of what plays shows: the speaker's line, the title's lines, the line under it, its time. */
@Immutable
data class WidgetWords(val speaker: Boolean, val titleLines: Int, val subtitle: Boolean, val time: Boolean = false) {
    fun needs(type: WidgetType, size: WordSize = WordSize.Regular): Float {
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

/**
 * What a widget [width] by [height] dp shows: its form, how many of its buttons, how large a cover
 * and how much of what plays. Where things stand is the layout's: the same frame on every side and
 * cells of equal width, whatever size the launcher finally gives the widget.
 */
@Immutable
data class WidgetFit(
    val form: WidgetForm,
    val width: Float,
    val height: Float,
    /** How many of the person's buttons stand, from the start of their order. */
    val buttons: Int,
    /** The cover's side in a bar, a player or a poster; 0 elsewhere. */
    val art: Float = 0f,
    val words: WidgetWords = WidgetWords.None,
    val size: WordSize = WordSize.Regular,
    /** A pad's buttons in each of its rows; the last row may hold fewer. */
    val columns: Int = buttons,
    /** A pad's cells' height. */
    val cell: Float = WidgetSizes.CELL_HEIGHT,
)

/** The widget's measures, in dp; the frame and the gap are res/values/widget.xml's. */
object WidgetSizes {
    /** The room on every side, inside the widget's edge: Android's own for widgets. */
    const val PADDING = 16f

    /**
     * Between two button cells. Each cell stands half of it inside its touch target, so the targets
     * meet and reach that half into the frame at a row's ends.
     */
    const val GAP = 4f

    /** Bare buttons draw nothing at a row's ends: their targets reach this near the widget's edge. */
    const val REACH = 4f

    /** A button's touch target is at least this wide. */
    const val CELL_MIN = 48f

    /** A row of cells under the words. */
    const val CELL_HEIGHT = 56f

    /** A bar's button: a cell of 48 in a target of 52. */
    const val BAR_CELL = 52f

    /** Between the words and the cells beside them. */
    const val WORDS_TO_CELLS = 8f

    /** From a cover to the words beside it. */
    const val ART_TO_WORDS = 12f

    /** A player's cover takes at most this share of its width. */
    const val ART_SHARE = 0.5f

    /** The words of a bar keep at least this much at the usual text size, or a button gives way. */
    const val WORDS_MIN = 80f

    const val GLYPH = 24f

    /** A Play … button's cover, inside its cell. */
    const val FACE = 28f

    const val TITLE_TO_SUBTITLE = 4f

    /**
     * The words' view stands this far outside its box at the start (widget_words_lead_out), and a
     * box past the frame on the side its words align to: each line moves back in by what its first
     * ink leaves, which no padding below none could do.
     */
    const val LEAD = 6f

    /** Under these a widget is one column, or one row, wide. */
    const val TWO_CELLS = 110f

    /** From here a widget is wide enough for a cover beside what plays. */
    const val WIDE = 300f
}

/**
 * The fit of a widget [width] by [height] dp for [buttons] buttons, set in [type]; with a time line
 * where there is [time], what plays unless it is left out ([words]), and [bare] buttons, which reach
 * into the frame.
 */
fun widgetFit(
    width: Float,
    height: Float,
    buttons: Int,
    type: WidgetType = WidgetType(),
    time: Boolean = false,
    words: Boolean = true,
    bare: Boolean = false,
): WidgetFit {
    val s = WidgetSizes
    val oneRow = height < s.TWO_CELLS
    val inner = width - 2 * s.PADDING
    val innerHeight = height - 2 * s.PADDING
    val row = span(width, bare)
    val form = when {
        width < s.TWO_CELLS && oneRow -> WidgetForm.Tile

        // Beside buttons a row narrower than a bar has no room for words.
        buttons > 0 && (!words || (oneRow && width < s.WIDE)) -> WidgetForm.Pad

        oneRow -> if (width >= s.WIDE) WidgetForm.Bar else WidgetForm.Line

        width >= s.WIDE -> WidgetForm.Player

        // A cover as wide as the frame, the speaker and the title under it, the cells: all with room.
        width >= s.TWO_CELLS && innerHeight >= inner + s.ART_TO_WORDS +
            SPEAKER_AND_TITLE.needs(type) + under(cells(row, buttons)) -> WidgetForm.Poster

        else -> WidgetForm.Card
    }
    return when (form) {
        WidgetForm.Tile -> WidgetFit(form, width, height, buttons = minOf(1, buttons))

        WidgetForm.Pad -> pad(width, height, buttons, bare)

        // Shown where no button stands, signed out too.
        WidgetForm.Line -> said(WidgetFit(form, width, height, 0), innerHeight, type, alone = true, strip = true)

        WidgetForm.Card -> {
            val shown = cells(row, buttons)
            said(WidgetFit(form, width, height, shown), innerHeight - under(shown), type, shown == 0, time)
        }

        WidgetForm.Bar -> {
            val art = innerHeight
            // Larger type keeps a bar's words their room: a button gives way first.
            val least = s.WORDS_MIN * type.title / WidgetType().title
            val room = inner - art - s.ART_TO_WORDS - least - s.WORDS_TO_CELLS
            val shown = floor((room + s.GAP + EPSILON) / s.BAR_CELL).toInt().coerceIn(0, buttons)
            said(WidgetFit(form, width, height, shown, art), innerHeight, type, shown == 0, strip = true)
        }

        WidgetForm.Poster -> {
            val shown = cells(row, buttons)
            val room = innerHeight - inner - s.ART_TO_WORDS - under(shown)
            said(WidgetFit(form, width, height, shown, art = inner), room, type, alone = false, time = time)
        }

        WidgetForm.Player -> {
            val shown = cells(row, buttons)
            val top = innerHeight - under(shown)
            val art = minOf(top, inner * s.ART_SHARE)
            said(WidgetFit(form, width, height, shown, art), top, type, shown == 0, time)
        }
    }
}

/** The width a row of targets spans: the frame and the half gaps its ends reach, or bare, nearly all. */
internal fun span(width: Float, bare: Boolean): Float =
    if (bare) width - 2 * WidgetSizes.REACH else width - 2 * WidgetSizes.PADDING + WidgetSizes.GAP

/**
 * The height of a row's targets for cells [cell] dp high in a widget [height] dp high: at least a
 * touch target, reaching past the frame above and below in a lower one, as far as bare buttons do
 * at the sides.
 */
internal fun target(cell: Float, height: Float): Float =
    maxOf(cell, minOf(WidgetSizes.CELL_MIN, height - 2 * WidgetSizes.REACH))

/** How many of [wanted] targets, each at least a touch target wide, stand side by side in [span] dp. */
internal fun cells(span: Float, wanted: Int): Int =
    floor((span + EPSILON) / WidgetSizes.CELL_MIN).toInt().coerceIn(0, wanted)

/**
 * Buttons alone: in as few rows as hold them, each as full as the next; cells as wide as their row
 * allows and about as high, the grid in the frame's middle.
 */
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

/** The height a row of cells takes beside the words, with the gap between. */
private fun under(cells: Int): Float = if (cells == 0) 0f else WidgetSizes.CELL_HEIGHT + WidgetSizes.WORDS_TO_CELLS

private val SPEAKER_AND_TITLE = WidgetWords(speaker = true, titleLines = 1, subtitle = false)
private val TITLE = WidgetWords(speaker = false, titleLines = 1, subtitle = false)

/**
 * [fit] with as much of what plays as [room] dp hold, and its size: a fifth larger where every line
 * has room; else the time gives way first, then the title's second line and the line under it; then
 * the speaker and the title grow smaller before the speaker's line goes. A third title line is a
 * card's without buttons; a [strip] one row high keeps the title to one.
 */
private fun said(
    fit: WidgetFit,
    room: Float,
    type: WidgetType,
    alone: Boolean,
    time: Boolean = false,
    strip: Boolean = false,
): WidgetFit {
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
    val (words, size) = listOf(SPEAKER_AND_TITLE to WordSize.Small, TITLE to WordSize.Regular, TITLE to WordSize.Small)
        .firstOrNull { (words, size) -> fits(words, size) } ?: (WidgetWords.None to WordSize.Regular)
    return fit.copy(words = words, size = size)
}

/** Every line: the speaker's, two of the title, the line under it, and the time where there is one. */
private fun WidgetWords.whole(time: Boolean) = speaker && titleLines >= 2 && subtitle && (this.time || !time)

private const val EPSILON = 0.01f
