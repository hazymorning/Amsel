package io.github.hazymorning.amsel.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/** The bar from what is stored: each known place once, Home always. */
class BarPlaceTest {
    @Test fun nothingStoredIsTheBarAsItComes() {
        assertEquals(BarPlace.entries, BarPlace.of(null))
    }

    @Test fun theStoredOrderHoldsAndUnknownOrRepeatedPlacesGo() {
        assertEquals(
            listOf(BarPlace.Library, BarPlace.Home, BarPlace.Speaker),
            BarPlace.of(listOf("library", "home", "radio", "speaker", "library")),
        )
    }

    @Test fun homeStaysEvenWhereItWasLeftOut() {
        assertEquals(listOf(BarPlace.Home, BarPlace.Search), BarPlace.of(listOf("search")))
        assertEquals(listOf(BarPlace.Home), BarPlace.of(emptyList()))
    }
}
