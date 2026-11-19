package io.github.hazymorning.amsel.widget

import io.github.hazymorning.amsel.widget.WidgetSizes.PADDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetFitTest {
    @Test fun eachSizeHasItsForm() {
        assertEquals(WidgetForm.Tile, widgetFit(80f, 96f, 3).form)
        assertEquals(WidgetForm.Row, widgetFit(172f, 96f, 3).form)
        assertEquals(WidgetForm.Card, widgetFit(172f, 204f, 3).form)
        assertEquals(WidgetForm.Bar, widgetFit(356f, 96f, 3).form)
        assertEquals(WidgetForm.Player, widgetFit(356f, 204f, 3).form)
    }

    /** Two cells wide, a phone's usual 172 dp: three targets of 48 and more across its frame. */
    @Test fun twoCellsHoldThreeButtons() {
        assertEquals(3, widgetFit(172f, 96f, 4).buttons)
        assertEquals(3, widgetFit(172f, 204f, 4).buttons)
        assertEquals(2, widgetFit(150f, 96f, 4).buttons)
    }

    /** Every button's target is at least a touch target wide, whatever the width. */
    @Test fun noTargetIsNarrowerThanATouchTarget() {
        for (width in 110..420 step 2) {
            val fit = widgetFit(width.toFloat(), 96f, 4)
            if (fit.buttons == 0) continue
            val target = (width - 2 * PADDING + WidgetSizes.GAP) / fit.buttons
            assertTrue("$width: $target", target >= WidgetSizes.CELL_MIN - 0.01f)
        }
    }

    @Test fun aTileTakesTheFirstButtonAlone() {
        assertEquals(1, widgetFit(80f, 96f, 3).buttons)
        assertEquals(0, widgetFit(80f, 96f, 0).buttons)
    }

    @Test fun aRowWithoutButtonsShowsWhatPlays() {
        assertEquals(WidgetWords(speaker = true, titleLines = 1, subtitle = false), widgetFit(172f, 96f, 0).words)
    }

    @Test fun noButtonsLeavesTheWordsTheirRoom() {
        assertEquals(WidgetWords(speaker = true, titleLines = 3, subtitle = true), widgetFit(172f, 204f, 0).words)
    }

    @Test fun theTitleTakesTwoLinesWhereTheHeightAllows() {
        assertEquals(1, widgetFit(172f, 150f, 3).words.titleLines)
        assertEquals(2, widgetFit(172f, 204f, 3).words.titleLines)
    }

    /** At 200 % the line under the title goes first, then its second line, then the speaker's. */
    @Test fun largeTypeGivesWayInOrder() {
        val large = WidgetType(speaker = 28f, title = 34f, subtitle = 28f)
        assertEquals(
            WidgetWords(speaker = true, titleLines = 1, subtitle = true),
            widgetFit(172f, 204f, 3, large).words,
        )
        assertEquals(
            WidgetWords(speaker = false, titleLines = 1, subtitle = false),
            widgetFit(172f, 140f, 3, large).words,
        )
    }

    /** A player's cover is as high as the room over its cells, and never more than half its width. */
    @Test fun aPlayersCoverFillsTheRoomBesideTheWords() {
        val fit = widgetFit(356f, 204f, 3)
        assertEquals(204f - 2 * PADDING - WidgetSizes.CELL_HEIGHT - WidgetSizes.WORDS_TO_CELLS, fit.art, 0.01f)
        assertEquals((356f - 2 * PADDING) / 2, widgetFit(356f, 400f, 3).art, 0.01f)
    }

    @Test fun aBarsCoverIsAsHighAsItsFrame() {
        val fit = widgetFit(356f, 96f, 3)
        assertEquals(96f - 2 * PADDING, fit.art, 0.01f)
        assertEquals(3, fit.buttons)
    }
}
