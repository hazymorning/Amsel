package io.github.hazymorning.amsel.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/** The bar from what is stored: each known place once, Home always. */
class BarPlaceTest {
    @Test fun nothingStoredIsTheBarAsItComes() {
        assertEquals(listOf(BarPlace.Home, BarPlace.Speaker, BarPlace.Search, BarPlace.Library), BarPlace.of(null))
    }

    @Test fun theKindsWaitTheirTurnAtTheEnd() {
        // A bar stored before the kinds could stand in it keeps its places; the kinds join it left out.
        val stored = BarPlace.arrangement(listOf("home", "speaker", "search", "library"))
        assertEquals(BarPlace.entries, stored.order)
        assertEquals(setOf(BarPlace.Home, BarPlace.Speaker, BarPlace.Search, BarPlace.Library), stored.shown)
        assertEquals(
            listOf(BarPlace.Home, BarPlace.Albums, BarPlace.Radio),
            BarPlace.of(listOf("home", "-speaker", "albums", "radio")),
        )
    }

    @Test fun theStoredOrderHoldsAndUnknownOrRepeatedPlacesGo() {
        assertEquals(
            listOf(BarPlace.Library, BarPlace.Home, BarPlace.Speaker),
            BarPlace.of(listOf("library", "home", "queue", "speaker", "library")),
        )
    }

    @Test fun homeStaysEvenWhereItWasLeftOut() {
        assertEquals(listOf(BarPlace.Home, BarPlace.Search), BarPlace.of(listOf("search")))
        assertEquals(listOf(BarPlace.Home), BarPlace.of(emptyList()))
    }
}
