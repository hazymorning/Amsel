package io.github.hazymorning.amsel.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a slim bar's title stands beside glyphs and beside a word, in a bar 1080 px wide. */
class BarTitleBoundsTest {
    @Test fun besideGlyphsTheTitleKeepsItsInsetHoweverLongItIs() {
        assertEquals(BarTitleBounds(240, 600), barTitleBounds(WIDTH, INSET, taken = 216, from = 72, needed = 2000))
        assertEquals(BarTitleBounds(240, 600), barTitleBounds(WIDTH, INSET, taken = INSET, from = 72, needed = 2000))
    }

    @Test fun aWordAmongTheActionsNarrowsTheMiddleFromBothSides() {
        assertEquals(BarTitleBounds(330, 420), barTitleBounds(WIDTH, INSET, taken = 330, from = 72, needed = 420))
    }

    @Test fun aTitleTooLongBetweenWordsHasAllTheRoomTheyLeave() {
        assertEquals(BarTitleBounds(72, 678), barTitleBounds(WIDTH, INSET, taken = 330, from = 72, needed = 421))
    }

    @Test fun aBarTooNarrowForATitleGivesItNoRoom() {
        assertEquals(BarTitleBounds(240, 0), barTitleBounds(400, INSET, taken = 216, from = 72, needed = 100))
        assertEquals(BarTitleBounds(72, 0), barTitleBounds(400, INSET, taken = 330, from = 72, needed = 100))
    }
}

private const val WIDTH = 1080
private const val INSET = 240
