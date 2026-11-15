package io.github.hazymorning.amsel.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SideSwipeTest {
    private val order = listOf("playlists", "albums", "artists")

    @Test fun aNeighbourComesInFromTheSideItLiesOn() {
        assertEquals(1, stepBetween(order, "playlists", "albums", going = 1))
        assertEquals(-1, stepBetween(order, "artists", "albums", going = -1))
    }

    @Test fun roundTheEndWhatASwipeBroughtKeepsTheSwipesWay() {
        assertEquals(1, stepBetween(order, "artists", "playlists", going = 1))
        assertEquals(-1, stepBetween(order, "playlists", "artists", going = -1))
    }

    @Test fun aTappedChipComesInFromItsOwnSide() {
        assertEquals(-1, stepBetween(order, "artists", "playlists", going = 0))
        assertEquals(1, stepBetween(order, "playlists", "artists", going = 1))
    }
}
