package io.github.hazymorning.amsel.widget

import androidx.compose.runtime.Immutable

/**
 * Where a widget's buttons stand in their row, in dp: the row's padding at its start, end and foot,
 * and how far the first and the last ink move inside their targets where the target would otherwise
 * reach past the row (positive: towards the end). The gaps between the targets share the rest.
 */
@Immutable
data class ButtonsPlace(
    val start: Float = 0f,
    val end: Float = 0f,
    val bottom: Float = 0f,
    val firstShift: Float = 0f,
    val lastShift: Float = 0f,
)

/** Where the first [WidgetFit.buttons] of [inks] stand, each ink in the middle of its target. */
fun WidgetFit.place(inks: List<Ink>): ButtonsPlace {
    val shown = inks.take(buttons)
    if (shown.isEmpty()) return ButtonsPlace()
    fun room(ink: Ink) = (WidgetSizes.TARGET - ink.width) / 2
    val start = room(shown.first())
    val end = room(shown.last())
    val single = shown.size == 1
    val foot = inset - (WidgetSizes.TARGET - shown.maxOf { it.height }) / 2
    return when (form) {
        WidgetForm.Tile -> ButtonsPlace()

        // One button stands in the middle of a row.
        WidgetForm.Row -> if (single) ButtonsPlace() else spread(inset - start, inset - end, bottom = 0f)

        WidgetForm.Card -> spread(inset - start, if (single) null else inset - end, foot)

        // The column starts at the cover's edge; the words keep their room from it.
        WidgetForm.Player -> spread(WidgetSizes.ART_TO_WORDS - start, if (single) null else inset - end, foot)

        WidgetForm.Bar -> ButtonsPlace(
            start = maxOf(0f, WidgetSizes.WORDS_TO_BUTTONS - start),
            end = maxOf(0f, inset - end),
            lastShift = -minOf(0f, inset - end),
        )
    }
}

/** A row's padding from its ideal: a target never reaches past the row, its ink moves inside it instead. */
private fun spread(start: Float, end: Float?, bottom: Float) = ButtonsPlace(
    start = maxOf(0f, start),
    end = maxOf(0f, end ?: 0f),
    bottom = maxOf(0f, bottom),
    firstShift = minOf(0f, start),
    lastShift = -minOf(0f, end ?: 0f),
)
