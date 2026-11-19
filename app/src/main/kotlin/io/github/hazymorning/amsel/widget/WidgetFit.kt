package io.github.hazymorning.amsel.widget

import androidx.compose.runtime.Immutable
import kotlin.math.floor

/** What a widget is at its size. */
enum class WidgetForm {
    /** One cell: one button in the middle, or a cover. */
    Tile,

    /** One row: the buttons side by side; without buttons, what plays. */
    Row,

    /** Two rows and more: the speaker and what plays, over the buttons. */
    Card,

    /** One row, four cells wide: the cover, what plays, the buttons at the end. */
    Bar,

    /** Two rows and more, four cells wide: the cover beside what plays, over the buttons. */
    Player,

    /** Two cells wide and tall enough: the cover across, what plays under it, the buttons at the foot. */
    Poster,
}

/** The words' line heights in dp at the phone's text size, which grows large text less than small. */
@Immutable
data class WidgetType(val speaker: Float = 16f, val title: Float = 20f, val subtitle: Float = 16f) {
    /** The same type [factor] times as large. */
    fun scaled(factor: Float) = WidgetType(speaker * factor, title * factor, subtitle * factor)
}

/** How much of what plays shows: the speaker's line, the title's lines, the line under it, its time. */
@Immutable
data class WidgetWords(val speaker: Boolean, val titleLines: Int, val subtitle: Boolean, val time: Boolean = false) {
    fun needs(type: WidgetType): Float = (if (speaker) type.speaker + WidgetSizes.SPEAKER_TO_TITLE else 0f) +
        type.title * titleLines +
        (if (subtitle) WidgetSizes.TITLE_TO_SUBTITLE + type.subtitle else 0f) +
        if (time) WidgetSizes.TITLE_TO_SUBTITLE + type.subtitle else 0f

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
    /** The cover's side in a bar or a player; 0 elsewhere. */
    val art: Float = 0f,
    val words: WidgetWords = WidgetWords.None,
    /** The words [WidgetSizes.LARGE] times as large: a card's or a player's with room for all of them. */
    val large: Boolean = false,
)

/** The widget's measures, in dp; the frame and the gap are res/values/widget.xml's. */
object WidgetSizes {
    /** The room on every side, inside the widget's edge. */
    const val PADDING = 12f

    /**
     * Between two button cells. Each cell stands half of it inside its touch target, so the targets
     * meet and reach that half into the frame at a row's ends.
     */
    const val GAP = 4f

    /** A button's touch target is at least this wide. */
    const val CELL_MIN = 48f

    /** A row of cells under the words. */
    const val CELL_HEIGHT = 56f

    /** A bar's button: a cell of 48 in a target of 52. */
    const val BAR_CELL = 52f

    /** Between the words and the cells under them, and before a bar's cells. */
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

    const val SPEAKER_TO_TITLE = 10f
    const val TITLE_TO_SUBTITLE = 4f

    /** Under these a widget is one column, or one row, wide. */
    const val TWO_CELLS = 110f

    /** From here a widget is wide enough for a cover beside what plays. */
    const val WIDE = 300f

    /** Words with room for every line they have grow by this, as type grows with a page. */
    const val LARGE = 1.2f
}

/** The fit of a widget [width] by [height] dp for [buttons] buttons, set in [type]; with a time line where there is [time]. */
fun widgetFit(
    width: Float,
    height: Float,
    buttons: Int,
    type: WidgetType = WidgetType(),
    time: Boolean = false,
): WidgetFit {
    val s = WidgetSizes
    val oneRow = height < s.TWO_CELLS
    val inner = width - 2 * s.PADDING
    val innerHeight = height - 2 * s.PADDING
    val form = when {
        width < s.TWO_CELLS && oneRow -> WidgetForm.Tile

        oneRow -> if (width >= s.WIDE) WidgetForm.Bar else WidgetForm.Row

        width >= s.WIDE -> WidgetForm.Player

        // A cover as wide as the frame, the speaker and the title under it, the cells: all with room.
        width >= s.TWO_CELLS && innerHeight >= inner + s.ART_TO_WORDS + type.speaker + s.SPEAKER_TO_TITLE +
            type.title + under(cells(inner, buttons)) -> WidgetForm.Poster

        else -> WidgetForm.Card
    }
    return when (form) {
        WidgetForm.Tile -> WidgetFit(form, width, height, buttons = minOf(1, buttons))

        // Shown where no button stands, signed out too.
        WidgetForm.Row -> WidgetFit(form, width, height, cells(inner, buttons), words = oneLine(innerHeight, type))

        WidgetForm.Card -> {
            val shown = cells(inner, buttons)
            val room = innerHeight - under(shown)
            val large = words(room, type.scaled(s.LARGE), shown == 0, time).takeIf { it.whole(time) }
            WidgetFit(
                form,
                width,
                height,
                shown,
                words = large ?: words(room, type, shown == 0, time),
                large =
                    large != null,
            )
        }

        WidgetForm.Bar -> {
            val art = innerHeight
            // Larger type keeps a bar's words their room: a button gives way first.
            val words = s.WORDS_MIN * type.title / WidgetType().title
            val room = inner - art - s.ART_TO_WORDS - words - s.WORDS_TO_CELLS
            val shown = floor((room + s.GAP + EPSILON) / s.BAR_CELL).toInt().coerceIn(0, buttons)
            WidgetFit(form, width, height, shown, art, oneLine(innerHeight, type))
        }

        WidgetForm.Poster -> {
            val shown = cells(inner, buttons)
            val room = innerHeight - inner - s.ART_TO_WORDS - under(shown)
            WidgetFit(form, width, height, shown, art = inner, words = words(room, type, alone = false, time))
        }

        WidgetForm.Player -> {
            val shown = cells(inner, buttons)
            val top = innerHeight - under(shown)
            val art = minOf(top, inner * s.ART_SHARE)
            val large = words(top, type.scaled(s.LARGE), shown == 0, time).takeIf { it.whole(time) }
            WidgetFit(form, width, height, shown, art, large ?: words(top, type, shown == 0, time), large != null)
        }
    }
}

/**
 * How many of [wanted] cells stand side by side in [room] dp: their targets, which reach half a gap
 * past the room at both ends, are each at least a touch target wide.
 */
internal fun cells(room: Float, wanted: Int): Int {
    val fit = floor((room + WidgetSizes.GAP + EPSILON) / WidgetSizes.CELL_MIN).toInt()
    return fit.coerceIn(0, wanted)
}

/** Every line: the speaker's, two of the title, the line under it, and the time where there is one. */
private fun WidgetWords.whole(time: Boolean) = speaker && titleLines >= 2 && subtitle && (this.time || !time)

/** The height a row of cells takes under the words, with the gap above it. */
private fun under(cells: Int): Float = if (cells == 0) 0f else WidgetSizes.CELL_HEIGHT + WidgetSizes.WORDS_TO_CELLS

/**
 * As much of what plays as [room] dp hold: the time gives way first, then the title's second line,
 * the line under the title, the speaker's line. A third line is a card's without buttons.
 */
private fun words(room: Float, type: WidgetType, alone: Boolean, time: Boolean): WidgetWords = listOfNotNull(
    WidgetWords(speaker = true, titleLines = 3, subtitle = true, time = true).takeIf { alone && time },
    WidgetWords(speaker = true, titleLines = 3, subtitle = true).takeIf { alone },
    WidgetWords(speaker = true, titleLines = 2, subtitle = true, time = true).takeIf { time },
    WidgetWords(speaker = true, titleLines = 2, subtitle = true),
    WidgetWords(speaker = true, titleLines = 1, subtitle = true),
    WidgetWords(speaker = true, titleLines = 1, subtitle = false),
    WidgetWords(speaker = false, titleLines = 1, subtitle = false),
).firstOrNull { it.needs(type) <= room + EPSILON } ?: WidgetWords.None

/** The speaker's line over the title, or the title alone, in one row [room] dp high. */
private fun oneLine(room: Float, type: WidgetType): WidgetWords = listOf(
    WidgetWords(speaker = true, titleLines = 1, subtitle = false),
    WidgetWords(speaker = false, titleLines = 1, subtitle = false),
).firstOrNull { it.needs(type) <= room + EPSILON } ?: WidgetWords.None

private const val EPSILON = 0.01f
