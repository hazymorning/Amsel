package io.github.hazymorning.amsel.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/** The bar from what is stored: each known place once, Home always. */
class BarItemTest {
    @Test fun nothingStoredIsTheBarAsItComes() {
        assertEquals(
            listOf(BarItem.Home, BarItem.Speaker, BarItem.Search, BarItem.Library, BarItem.Settings),
            BarItem.of(null),
        )
    }

    @Test fun aBarStoredBeforeSettingsTakesThemAtItsEndWhereThereIsRoom() {
        assertEquals(
            listOf(BarItem.Home, BarItem.Search, BarItem.Settings),
            BarItem.of(listOf("home", "-speaker", "search")),
        )
        // Full, it stays as it was; Settings wait, left out, at the end.
        val full = BarItem.arrangement(listOf("home", "speaker", "search", "library", "albums"))
        assertEquals(
            listOf(BarItem.Home, BarItem.Speaker, BarItem.Search, BarItem.Library, BarItem.Albums),
            full.visible,
        )
        assertEquals(BarItem.Settings, full.order.last())
        // Once stored with Settings out, they stay out.
        assertEquals(listOf(BarItem.Home, BarItem.Search), BarItem.of(listOf("home", "search", "-settings")))
    }

    @Test fun theKindsWaitTheirTurnAtTheEnd() {
        // A bar stored before the kinds could stand in it keeps its places; the kinds join it left out.
        val stored = BarItem.arrangement(listOf("home", "speaker", "search", "library"))
        assertEquals(BarItem.entries, stored.order)
        assertEquals(
            setOf(BarItem.Home, BarItem.Speaker, BarItem.Search, BarItem.Library, BarItem.Settings),
            stored.shown,
        )
        assertEquals(
            listOf(BarItem.Home, BarItem.Albums, BarItem.Radio),
            BarItem.of(listOf("home", "-speaker", "albums", "radio", "-settings")),
        )
    }

    @Test fun theStoredOrderHoldsAndUnknownOrRepeatedPlacesGo() {
        assertEquals(
            listOf(BarItem.Library, BarItem.Home, BarItem.Speaker),
            BarItem.of(listOf("library", "home", "queue", "speaker", "library", "-settings")),
        )
    }

    @Test fun homeStaysEvenWhereItWasLeftOut() {
        assertEquals(listOf(BarItem.Home, BarItem.Search), BarItem.of(listOf("search", "-settings")))
        assertEquals(listOf(BarItem.Home), BarItem.of(listOf("-settings")))
    }
}
