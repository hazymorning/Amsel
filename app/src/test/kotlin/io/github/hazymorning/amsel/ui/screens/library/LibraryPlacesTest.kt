package io.github.hazymorning.amsel.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPlacesTest {
    private fun LibraryPlaces.at(kind: LibraryKind): Pair<Int, Int> = if (kind.covers) {
        grid(kind).let { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset }
    } else {
        list(kind).let { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset }
    }

    @Test fun fromWhereTheHeaderShowsTheNextKindOpensAtTheSameHeight() {
        val places = LibraryPlaces(mapOf("Albums" to listOf(0, 40), "Artists" to listOf(30, 12)))
        places.handOver(LibraryKind.Albums, LibraryKind.Artists)
        assertEquals(0 to 40, places.at(LibraryKind.Artists))
    }

    @Test fun fromFurtherDownTheNextKindKeepsItsPlacePastItsHeader() {
        val places = LibraryPlaces(mapOf("Albums" to listOf(12, 0), "Artists" to listOf(30, 12)))
        places.handOver(LibraryKind.Albums, LibraryKind.Artists)
        assertEquals(30 to 12, places.at(LibraryKind.Artists))
    }

    @Test fun fromFurtherDownTheNextKindOpensJustPastItsHeaderWhereItShowedIt() {
        val places = LibraryPlaces(mapOf("Artists" to listOf(30, 12), "Albums" to listOf(0, 0)))
        places.handOver(LibraryKind.Artists, LibraryKind.Albums)
        assertEquals(1 to 0, places.at(LibraryKind.Albums))
    }
}
