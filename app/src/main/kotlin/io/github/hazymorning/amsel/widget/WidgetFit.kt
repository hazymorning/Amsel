package io.github.hazymorning.amsel.widget

import androidx.compose.runtime.Immutable

/** What a widget is at its size. */
enum class WidgetForm {
    /** One cell: one button in the middle, or a cover. */
    Tile,

    /** One row: the buttons, spread from the start to the end; without buttons, what plays. */
    Row,

    /** Two rows and more: the speaker and what plays, over the buttons. */
    Card,

    /** One row, four cells wide: the cover, what plays, the buttons at the end. */
    Bar,

    /** Two rows and more, four cells wide: the cover at the start, what plays over the buttons beside it. */
    Player,
}

/** What shows of a button, in dp: the disc, a cover or a glyph's own ink, standing in the middle of its target. */
@Immutable
data class Ink(val width: Float, val height: Float)

/** The words' line heights in dp at the phone's text size, which grows large text less than small. */
@Immutable
data class WidgetType(val speaker: Float = 16f, val title: Float = 20f, val subtitle: Float = 16f)

/** How much of what plays shows: the speaker's line, the title's lines, the line under it. */
@Immutable
data class WidgetWords(val speaker: Boolean, val titleLines: Int, val subtitle: Boolean) {
    fun needs(type: WidgetType): Float = (if (speaker) type.speaker + WidgetSizes.SPEAKER_TO_TITLE else 0f) +
        type.title * titleLines +
        if (subtitle) WidgetSizes.TITLE_TO_SUBTITLE + type.subtitle else 0f

    companion object {
        val None = WidgetWords(speaker = false, titleLines = 0, subtitle = false)
    }
}

/**
 * How a widget [width] by [height] dp lays out. Every distance is to the ink, so a glyph's or the
 * disc's own room inside its 48 dp target never shows as a gap.
 */
@Immutable
data class WidgetFit(
    val form: WidgetForm,
    val width: Float,
    val height: Float,
    /** The room at the edges, to the ink. */
    val inset: Float,
    /** How many of the person's buttons stand, from the start of their order. */
    val buttons: Int,
    /** The cover's side in a bar or a player; 0 elsewhere. */
    val art: Float = 0f,
    val words: WidgetWords = WidgetWords.None,
)

/** The widget's measures, in dp. */
object WidgetSizes {
    /** Every button's touch target. */
    const val TARGET = 48f
    const val DISC = 40f
    const val GLYPH = 24f

    /** A Play … button's cover. */
    const val FACE = 28f

    /** A tile's one button, on a ground of its own. */
    const val TILE_GROUND = 44f

    /** The room at the edges of a card and a player, as the words keep from any filled shape. */
    const val INSET = 16f

    /** A row's room at its ends follows its room above and below, never less than this. */
    const val ROW_MIN = 12f

    /** A bar's room around its cover, which the bar's height gives, at least this. */
    const val BAR_MIN = 8f

    /** From the cover to the words, as on the dock. */
    const val ART_TO_WORDS = 16f

    /** A player's cover takes at most this share of the room beside it. */
    const val ART_SHARE = 0.5f

    /** Between the words and the buttons beside or under them. */
    const val WORDS_TO_BUTTONS = 12f

    /** The words of a bar get at least this much, or a button gives way. */
    const val WORDS_MIN = 96f

    const val SPEAKER_TO_TITLE = 10f
    const val TITLE_TO_SUBTITLE = 4f

    /** Under these a widget is one column, or one row, wide. */
    const val TWO_CELLS = 110f

    /** From here a widget is wide enough for the cover beside what plays. */
    const val WIDE = 300f
}

/** The fit of a widget [width] by [height] dp for the person's buttons, [inks] in their order. */
fun widgetFit(width: Float, height: Float, inks: List<Ink>, type: WidgetType = WidgetType()): WidgetFit {
    val s = WidgetSizes
    val oneRow = height < s.TWO_CELLS
    val form = when {
        width < s.TWO_CELLS && oneRow -> WidgetForm.Tile
        oneRow -> if (width >= s.WIDE) WidgetForm.Bar else WidgetForm.Row
        else -> if (width >= s.WIDE) WidgetForm.Player else WidgetForm.Card
    }
    return when (form) {
        WidgetForm.Tile -> WidgetFit(form, width, height, inset = 0f, buttons = minOf(1, inks.size))

        WidgetForm.Row -> row(width, height, inks, type)

        WidgetForm.Card -> {
            val buttons = fitting(inks, width - 2 * s.INSET, s.INSET, s.INSET)
            val room = height - 2 * s.INSET - buttonsHeight(inks, buttons)
            WidgetFit(form, width, height, s.INSET, buttons, words = words(room, type, alone = buttons == 0))
        }

        WidgetForm.Bar -> {
            val room = ((height - s.TARGET) / 2).coerceIn(s.BAR_MIN, s.INSET)
            val art = height - 2 * room
            val wordsEnd = room + art + s.ART_TO_WORDS + s.WORDS_MIN
            val buttons = adjacent(inks, width - wordsEnd - room, room)
            WidgetFit(form, width, height, room, buttons, art, oneLine(height - 2 * s.BAR_MIN, type))
        }

        WidgetForm.Player -> {
            val art = minOf(height - 2 * s.INSET, (width - 2 * s.INSET - s.ART_TO_WORDS) * s.ART_SHARE)
            val column = width - 2 * s.INSET - art - s.ART_TO_WORDS
            val buttons = fitting(inks, column, s.ART_TO_WORDS, s.INSET)
            val room = height - 2 * s.INSET - buttonsHeight(inks, buttons)
            WidgetFit(form, width, height, s.INSET, buttons, art, words(room, type, alone = buttons == 0))
        }
    }
}

/**
 * A row: the buttons in the middle of its height, and at its ends the same room as above and below
 * its tallest ink, less only where that room would cost a button, never under
 * [WidgetSizes.ROW_MIN]. Without buttons, or none that fit, what plays.
 */
private fun row(width: Float, height: Float, inks: List<Ink>, type: WidgetType): WidgetFit {
    val most = fitting(inks, width - 2 * WidgetSizes.ROW_MIN, WidgetSizes.ROW_MIN, WidgetSizes.ROW_MIN)
    val tallest = inks.take(most).maxOfOrNull { it.height }
    if (tallest == null) {
        val words = oneLine(height - 2 * WidgetSizes.ROW_MIN, type)
        return WidgetFit(WidgetForm.Row, width, height, WidgetSizes.INSET, buttons = 0, words = words)
    }
    var inset = maxOf(WidgetSizes.ROW_MIN, (height - tallest) / 2)
    while (inset > WidgetSizes.ROW_MIN && fitting(inks, width - 2 * inset, inset, inset) < most) {
        inset = maxOf(WidgetSizes.ROW_MIN, inset - ROW_STEP)
    }
    return WidgetFit(WidgetForm.Row, width, height, inset, most)
}

/**
 * How many of [inks] stand spread over [span] dp, from the first's ink to the last's: each target is
 * 48 dp wide and centred on its ink, so the outer two reach past the span by their own room, at most
 * [before] and [after] dp (to the widget's edge, or into the room beside a cover), and no two
 * targets overlap.
 */
internal fun fitting(inks: List<Ink>, span: Float, before: Float, after: Float): Int {
    val t = WidgetSizes.TARGET
    fun room(ink: Ink) = (t - ink.width) / 2
    for (n in inks.size downTo 2) {
        val reach = span + minOf(before, room(inks[0])) + minOf(after, room(inks[n - 1]))
        if (n * t <= reach + EPSILON) return n
    }
    return if (inks.isNotEmpty() && t <= span + minOf(before, room(inks[0])) + after + EPSILON) 1 else 0
}

/**
 * How many of [inks] stand target by target at a bar's end, in [room] dp from the words' end to the
 * last one's ink, which keeps the bar's [inset] from the edge; no target reaches past the edge.
 */
private fun adjacent(inks: List<Ink>, room: Float, inset: Float): Int {
    val t = WidgetSizes.TARGET
    for (n in inks.size downTo 1) {
        val lead = maxOf(0f, WidgetSizes.WORDS_TO_BUTTONS - (t - inks[0].width) / 2)
        val needs = lead + n * t - (t - inks[n - 1].width) / 2
        if (needs <= room + EPSILON && lead + n * t <= room + inset + EPSILON) return n
    }
    return 0
}

/** The height the buttons take under the words: their tallest ink and the room above it. */
private fun buttonsHeight(inks: List<Ink>, buttons: Int): Float =
    inks.take(buttons).maxOfOrNull { it.height + WidgetSizes.WORDS_TO_BUTTONS } ?: 0f

/**
 * As much of what plays as [room] dp hold: the line under the title gives way first, then the
 * title's second line, then the speaker's line. A third line is a card's without buttons.
 */
private fun words(room: Float, type: WidgetType, alone: Boolean): WidgetWords = listOfNotNull(
    WidgetWords(speaker = true, titleLines = 3, subtitle = true).takeIf { alone },
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

/** How finely a row gives up room at its ends for a button, in dp. */
private const val ROW_STEP = 0.25f
