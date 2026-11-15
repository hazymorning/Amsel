package io.github.hazymorning.amsel.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a slim bar's title stands beside glyphs and beside a word, in a bar 1080 px wide. */
class BarTitleRoomTest {
    @Test fun besideGlyphsTheTitleKeepsItsInsetHoweverLongItIs() {
        assertEquals(BarTitleRoom(240, 600), barTitleRoom(WIDTH, INSET, taken = 216, from = 72, needed = 2000))
        assertEquals(BarTitleRoom(240, 600), barTitleRoom(WIDTH, INSET, taken = INSET, from = 72, needed = 2000))
    }

    @Test fun aWordAmongTheActionsNarrowsTheMiddleFromBothSides() {
        assertEquals(BarTitleRoom(330, 420), barTitleRoom(WIDTH, INSET, taken = 330, from = 72, needed = 420))
    }

    @Test fun aTitleTooLongBetweenWordsHasAllTheRoomTheyLeave() {
        assertEquals(BarTitleRoom(72, 678), barTitleRoom(WIDTH, INSET, taken = 330, from = 72, needed = 421))
    }

    @Test fun aBarTooNarrowForATitleGivesItNoRoom() {
        assertEquals(BarTitleRoom(240, 0), barTitleRoom(400, INSET, taken = 216, from = 72, needed = 100))
        assertEquals(BarTitleRoom(72, 0), barTitleRoom(400, INSET, taken = 330, from = 72, needed = 100))
    }
}

private const val WIDTH = 1080
private const val INSET = 240
