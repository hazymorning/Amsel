package io.github.hazymorning.amsel.widget

import io.github.hazymorning.amsel.widget.WidgetSizes.INSET
import io.github.hazymorning.amsel.widget.WidgetSizes.TARGET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetFitTest {
    /** The inks of Amsel's glyphs at 24 dp (IconInk), the play disc and a Play … button's cover. */
    private val skip = Ink(15f, 18f)
    private val speakers = Ink(13.5f, 21f)
    private val quieter = Ink(18f, 19.5f)
    private val disc = Ink(40f, 40f)
    private val face = Ink(28f, 28f)

    private val four = listOf(skip, disc, skip, speakers)
    private val transport = listOf(skip, disc, skip)

    @Test fun eachSizeHasItsForm() {
        assertEquals(WidgetForm.Tile, widgetFit(76f, 76f, four).form)
        assertEquals(WidgetForm.Row, widgetFit(156f, 76f, four).form)
        assertEquals(WidgetForm.Card, widgetFit(156f, 156f, four).form)
        assertEquals(WidgetForm.Bar, widgetFit(350f, 76f, four).form)
        assertEquals(WidgetForm.Player, widgetFit(350f, 170f, four).form)
    }

    /** At 48 dp a target, four do not fit a narrow card: the fourth gives way whole. */
    @Test fun aNarrowCardKeepsWhatFits() {
        assertEquals(3, widgetFit(156f, 156f, four).buttons)
        assertEquals(4, widgetFit(200f, 200f, four).buttons)
    }

    @Test fun aRowOfTwoCellsTakesTheTransport() {
        val fit = widgetFit(156f, 76f, transport)
        assertEquals(3, fit.buttons)
        // As much room at the ends as above and below the disc.
        assertEquals((76f - 40f) / 2, fit.inset, 0.01f)
    }

    @Test fun aRowsEndsKeepItsRoomAboveAndBelow() {
        assertEquals((104f - 40f) / 2, widgetFit(182f, 104f, transport).inset, 0.01f)
        assertEquals(WidgetSizes.ROW_MIN, widgetFit(140f, 50f, transport).inset, 0.01f)
    }

    /** Three glyphs in a tall row: the ends give up some of their room rather than the third button. */
    @Test fun aRowsEndsGiveWayBeforeAButton() {
        val fit = widgetFit(182f, 104f, listOf(skip, quieter, skip))
        assertEquals(3, fit.buttons)
        assertTrue(fit.inset < (104f - 19.5f) / 2)
    }

    @Test fun aTileTakesTheFirstButtonAlone() {
        assertEquals(1, widgetFit(76f, 76f, four).buttons)
        assertEquals(0, widgetFit(76f, 76f, emptyList()).buttons)
    }

    @Test fun aRowWithoutButtonsShowsWhatPlays() {
        val fit = widgetFit(156f, 76f, emptyList())
        assertEquals(WidgetWords(speaker = true, titleLines = 1, subtitle = false), fit.words)
    }

    @Test fun noButtonsLeavesTheWordsTheirRoom() {
        val fit = widgetFit(156f, 156f, emptyList())
        assertEquals(WidgetWords(speaker = true, titleLines = 3, subtitle = true), fit.words)
    }

    @Test fun theTitleTakesTwoLinesWhereTheHeightAllows() {
        assertEquals(1, widgetFit(156f, 156f, transport).words.titleLines)
        assertEquals(2, widgetFit(180f, 190f, transport).words.titleLines)
    }

    /** At 200 % the line under the title goes first, then its second line, then the speaker's. */
    @Test fun largeTypeGivesWayInOrder() {
        val large = WidgetType(speaker = 28f, title = 34f, subtitle = 28f)
        assertEquals(
            WidgetWords(speaker = true, titleLines = 1, subtitle = true),
            widgetFit(180f, 190f, transport, large).words,
        )
        assertEquals(
            WidgetWords(speaker = true, titleLines = 1, subtitle = false),
            widgetFit(180f, 180f, transport, large).words,
        )
        assertEquals(
            WidgetWords(speaker = false, titleLines = 1, subtitle = false),
            widgetFit(156f, 150f, transport, large).words,
        )
    }

    @Test fun aPlayersCoverStandsAtTheInset() {
        val fit = widgetFit(350f, 170f, four)
        assertEquals(170f - 2 * INSET, fit.art, 0.01f)
        assertEquals(4, fit.buttons)
    }

    @Test fun aTallPlayersCoverLeavesRoomBesideIt() {
        val fit = widgetFit(350f, 300f, four)
        assertEquals((350f - 2 * INSET - WidgetSizes.ART_TO_WORDS) / 2, fit.art, 0.01f)
    }

    @Test fun aBarKeepsTheDocksCover() {
        val fit = widgetFit(350f, 76f, four)
        assertEquals(48f, fit.art, 0.01f)
        assertEquals(3, fit.buttons)
    }

    /**
     * Laid out as the row lays them out (equal gaps between the targets), every first ink stands at
     * its inset, every last one as far from the end, the tallest as far from the foot, no target
     * leaves the widget and no two overlap.
     */
    @Test fun everyInkStandsAtItsInset() {
        val orders =
            listOf(four, transport, listOf(disc, skip), listOf(face, quieter, skip, face), listOf(speakers, skip))
        for (width in 104..420 step 4) {
            for (height in listOf(76f, 120f, 156f, 200f)) {
                for (inks in orders) {
                    val fit = widgetFit(width.toFloat(), height, inks)
                    if (fit.form == WidgetForm.Tile || fit.buttons < 2 || fit.form == WidgetForm.Bar) continue
                    check(fit, inks.take(fit.buttons))
                }
            }
        }
    }

    private fun check(fit: WidgetFit, shown: List<Ink>) {
        val place = fit.place(shown)
        val name = "${fit.form} ${fit.width}x${fit.height} $shown"
        val rowStart = if (fit.form == WidgetForm.Player) INSET + fit.art else 0f
        val left = rowStart + place.start
        val right = fit.width - place.end
        val gap = (right - left - shown.size * TARGET) / (shown.size - 1)
        assertTrue(name, gap >= -0.01f && left >= -0.01f && right <= fit.width + 0.01f)
        val firstInk = left + (TARGET - shown.first().width) / 2 + place.firstShift
        val lastTarget = left + (shown.size - 1) * (TARGET + gap)
        val lastInk = lastTarget + (TARGET + shown.last().width) / 2 + place.lastShift
        val wordsStart = if (fit.form == WidgetForm.Player) rowStart + WidgetSizes.ART_TO_WORDS else fit.inset
        assertEquals(name, wordsStart, firstInk, 0.01f)
        assertEquals(name, fit.width - fit.inset, lastInk, 0.01f)
        if (fit.form != WidgetForm.Row) {
            val foot = place.bottom + (TARGET - shown.maxOf { it.height }) / 2
            assertEquals(name, fit.inset, foot, 0.01f)
        }
        assertFalse(name, fit.inset < WidgetSizes.ROW_MIN)
    }
}
