package io.github.hazymorning.amsel.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/** The bar from what is stored: each known place once, Home always. */
class BarPlaceTest {
    @Test fun nothingStoredIsTheBarAsItComes() {
        assertEquals(
            listOf(BarPlace.Home, BarPlace.Speaker, BarPlace.Search, BarPlace.Library, BarPlace.Settings),
            BarPlace.of(null),
        )
    }

    @Test fun aBarStoredBeforeSettingsTakesThemAtItsEndWhereThereIsRoom() {
        assertEquals(
            listOf(BarPlace.Home, BarPlace.Search, BarPlace.Settings),
            BarPlace.of(listOf("home", "-speaker", "search")),
        )
        // Full, it stays as it was; Settings wait, left out, at the end.
        val full = BarPlace.arrangement(listOf("home", "speaker", "search", "library", "albums"))
        assertEquals(
            listOf(BarPlace.Home, BarPlace.Speaker, BarPlace.Search, BarPlace.Library, BarPlace.Albums),
            full.visible,
        )
        assertEquals(BarPlace.Settings, full.order.last())
        // Once stored with Settings out, they stay out.
        assertEquals(listOf(BarPlace.Home, BarPlace.Search), BarPlace.of(listOf("home", "search", "-settings")))
    }

    @Test fun theKindsWaitTheirTurnAtTheEnd() {
        // A bar stored before the kinds could stand in it keeps its places; the kinds join it left out.
        val stored = BarPlace.arrangement(listOf("home", "speaker", "search", "library"))
        assertEquals(BarPlace.entries, stored.order)
        assertEquals(
            setOf(BarPlace.Home, BarPlace.Speaker, BarPlace.Search, BarPlace.Library, BarPlace.Settings),
            stored.shown,
        )
        assertEquals(
            listOf(BarPlace.Home, BarPlace.Albums, BarPlace.Radio),
            BarPlace.of(listOf("home", "-speaker", "albums", "radio", "-settings")),
        )
    }

    @Test fun theStoredOrderHoldsAndUnknownOrRepeatedPlacesGo() {
        assertEquals(
            listOf(BarPlace.Library, BarPlace.Home, BarPlace.Speaker),
            BarPlace.of(listOf("library", "home", "queue", "speaker", "library", "-settings")),
        )
    }

    @Test fun homeStaysEvenWhereItWasLeftOut() {
        assertEquals(listOf(BarPlace.Home, BarPlace.Search), BarPlace.of(listOf("search", "-settings")))
        assertEquals(listOf(BarPlace.Home), BarPlace.of(listOf("-settings")))
    }
}
