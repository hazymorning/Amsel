package io.github.hazymorning.amsel.ui.navigation

import io.github.hazymorning.amsel.ui.screens.library.LibraryKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** What is stored for the bar and the mini player, read back, from this version and an earlier one. */
class ArrangementTest {
    @Test fun theOrderKeepsWhatIsLeftOutWhereItStands() {
        val bar = BarPlace.arrangement(null).with(BarPlace.Search, on = false)
        val tokens = bar.tokens(BarPlace::id)
        assertEquals(
            listOf("home", "speaker", "-search", "library") +
                listOf("playlists", "albums", "artists", "radio", "audiobooks", "podcasts").map { "-$it" },
            tokens,
        )
        assertEquals(bar, BarPlace.arrangement(tokens))
    }

    @Test fun anEarlierBarStoredOnlyWhatItShowedTheRestFollowsLeftOut() {
        val bar = BarPlace.arrangement(listOf("library", "home"))
        assertEquals(
            listOf(BarPlace.Library, BarPlace.Home, BarPlace.Speaker, BarPlace.Search) + BarPlace.entries.drop(4),
            bar.order,
        )
        assertEquals(listOf(BarPlace.Library, BarPlace.Home), bar.visible)
    }

    @Test fun homeAndPlayAlwaysStay() {
        assertEquals(true, BarPlace.Home in BarPlace.arrangement(listOf("-home", "search")).shown)
        assertEquals(true, DockControl.PlayPause in DockControl.arrangement(listOf("-play", "next")).shown)
    }

    @Test fun theLibraryKeepsOneKindAtLeast() {
        assertEquals(LibraryKind.entries, LibraryKind.arrangement(null).visible)
        val none = LibraryKind.arrangement(LibraryKind.entries.map { "-" + it.id })
        assertEquals(listOf(LibraryKind.Downloads), none.visible)
        val stored = LibraryKind.arrangement(listOf("radio", "-albums", "artists"))
        assertEquals(listOf(LibraryKind.Radio, LibraryKind.Artists), stored.visible)
        assertEquals(stored, LibraryKind.arrangement(stored.tokens(LibraryKind::id)))
    }

    @Test fun theMiniPlayerStartsWithPlayAndNext() {
        assertEquals(listOf(DockControl.PlayPause, DockControl.Next), DockControl.arrangement(null).visible)
        val moved = DockControl.arrangement(null).moved(1, 0)
        assertEquals(listOf(DockControl.Next, DockControl.PlayPause), moved.visible)
        assertEquals(moved, DockControl.arrangement(moved.tokens(DockControl::id)))
    }
}
