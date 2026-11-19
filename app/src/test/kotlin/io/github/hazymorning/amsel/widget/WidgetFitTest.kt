package io.github.hazymorning.amsel.widget

import io.github.hazymorning.amsel.widget.WidgetSizes.PADDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetFitTest {
    @Test fun eachSizeHasItsForm() {
        assertEquals(WidgetForm.Tile, widgetFit(80f, 96f, 3).form)
        assertEquals(WidgetForm.Pad, widgetFit(172f, 96f, 3).form)
        assertEquals(WidgetForm.Line, widgetFit(172f, 96f, 0).form)
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

    @Test fun aTileTakesOneButtonAlone() {
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

    @Test fun theTimeGivesWayFirst() {
        val roomy = widgetFit(172f, 204f, buttons = 3, time = true).words
        assertEquals(WidgetWords(speaker = true, titleLines = 2, subtitle = true, time = true), roomy)
        val tight = widgetFit(172f, 184f, buttons = 3, time = true).words
        assertEquals(WidgetWords(speaker = true, titleLines = 2, subtitle = true), tight)
    }

    /** Type grows a fifth on a widget where all its lines have room at that size, and only there. */
    @Test fun wordsGrowWhereEveryLineHasRoom() {
        assertEquals(WordSize.Regular, widgetFit(172f, 204f, buttons = 3, time = true).size)
        assertEquals(WordSize.Large, widgetFit(172f, 260f, buttons = 3, time = true).size)
        assertEquals(WordSize.Regular, widgetFit(356f, 204f, buttons = 5, time = true).size)
        assertEquals(WordSize.Large, widgetFit(356f, 320f, buttons = 5, time = true).size)
    }

    /**
     * A strip too low for the speaker's line over the title keeps both, an eighth smaller, standing in
     * its middle by their ink; lower still, the title alone.
     */
    @Test fun aStripKeepsTwoLinesInSmallerType() {
        val speakerAndTitle = WidgetWords(speaker = true, titleLines = 1, subtitle = false)
        assertEquals(speakerAndTitle to WordSize.Regular, widgetFit(356f, 96f, 3).let { it.words to it.size })
        assertEquals(speakerAndTitle to WordSize.Small, widgetFit(356f, 76f, 3).let { it.words to it.size })
        assertEquals(speakerAndTitle to WordSize.Small, widgetFit(356f, 66f, 3).let { it.words to it.size })
        assertEquals(speakerAndTitle to WordSize.Small, widgetFit(356f, 62f, 3).let { it.words to it.size })
        assertEquals(WidgetWords(speaker = false, titleLines = 1, subtitle = false), widgetFit(356f, 58f, 3).words)
    }

    /** Bare buttons reach into the frame: a small square holds three where two cells stand. */
    @Test fun bareButtonsFitMore() {
        assertEquals(2, widgetFit(156f, 156f, buttons = 3).buttons)
        assertEquals(3, widgetFit(156f, 156f, buttons = 3, bare = true).buttons)
        assertEquals(3, widgetFit(156f, 96f, buttons = 4, bare = true).buttons)
    }

    /** Without what plays the buttons fill the widget: in as few rows as hold them, cells about square. */
    @Test fun withoutWhatPlaysTheButtonsMakeAPad() {
        val four = widgetFit(172f, 204f, buttons = 4, words = false)
        assertEquals(WidgetForm.Pad, four.form)
        assertEquals(4, four.buttons)
        assertEquals(2, four.columns)
        assertEquals((172f - 2 * PADDING + WidgetSizes.GAP) / 2 - WidgetSizes.GAP, four.cell, 0.01f)
        val three = widgetFit(172f, 204f, buttons = 3, words = false)
        assertEquals(3, three.columns)
        assertEquals(WidgetSizes.CELL_HEIGHT, three.cell, 0.01f)
        val five = widgetFit(172f, 204f, buttons = 5, words = false)
        assertEquals(5, five.buttons)
        assertEquals(3, five.columns)
        assertEquals(8, widgetFit(356f, 204f, buttons = 8, words = false).buttons)
    }

    /** Two cells wide and three high, the cover stands across, as on a small player; its words under it. */
    @Test fun aTallNarrowWidgetShowsItsCoverAcross() {
        val fit = widgetFit(172f, 312f, buttons = 3, time = true)
        assertEquals(WidgetForm.Poster, fit.form)
        assertEquals(140f, fit.art, 0.01f)
        assertEquals(3, fit.buttons)
        assertTrue(fit.words.speaker && fit.words.titleLines >= 1)
        assertEquals(WidgetForm.Card, widgetFit(172f, 260f, buttons = 3).form)
    }

    @Test fun aWideWidgetHoldsMoreThanFourButtons() {
        assertEquals(5, widgetFit(356f, 204f, buttons = 5).buttons)
        assertEquals(6, widgetFit(356f, 204f, buttons = 8).buttons)
        assertEquals(8, widgetFit(500f, 204f, buttons = 8).buttons)
    }

    /** Where fewer fit than are set, play and pause stays, and the others give way from the start. */
    @Test fun playStaysAndTheOthersGiveWayFromTheStart() {
        val set = DefaultSlots.map { it.button }
        fun kept(room: Int, buttons: List<WidgetButton> = set) = standing(buttons, room) { it }
        assertEquals(set, kept(5))
        assertEquals(listOf(WidgetButton.Speakers, WidgetButton.PlayPause, WidgetButton.Next), kept(3))
        assertEquals(listOf(WidgetButton.PlayPause, WidgetButton.Next), kept(2))
        assertEquals(listOf(WidgetButton.PlayPause), kept(1))
        assertEquals(emptyList<WidgetButton>(), kept(0))
        val noPlay = listOf(WidgetButton.Quieter, WidgetButton.Louder, WidgetButton.Speakers)
        assertEquals(listOf(WidgetButton.Louder, WidgetButton.Speakers), kept(2, noPlay))
        val playFirst = listOf(WidgetButton.PlayPause, WidgetButton.Previous, WidgetButton.Next)
        assertEquals(listOf(WidgetButton.PlayPause, WidgetButton.Next), kept(2, playFirst))
    }
}
