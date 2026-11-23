package io.github.hazymorning.amsel.ui.screens.library

import androidx.compose.runtime.Composable
import io.github.hazymorning.amsel.api.LibrarySort
import io.github.hazymorning.amsel.sample.LibrarySampleData
import io.github.hazymorning.amsel.sample.SampleData
import io.github.hazymorning.amsel.testing.ScreenshotTest
import io.github.hazymorning.amsel.testing.ShotVariant
import org.junit.Test

class LibraryShots : ScreenshotTest() {
    /** Blue Hour plays (Lanterns, in the kitchen): its ring is the only colour besides the covers. */
    @Test fun albums() = snap("library_albums", listOf(ShotVariant.LargeFont)) {
        val playing = setOf(SampleData.albums.first().key)
        Library(LibraryUiState(LibraryKind.Albums, LibrarySort.RecentlyAdded, SampleData.albums, playing = playing))
    }

    @Test fun empty() = snap("library_empty") { Library(LibraryUiState(LibraryKind.Albums)) }

    /** Albums as a place of their own in the bar: their name is the title, with no chips. */
    @Test fun alone() = snap("library_alone") {
        Library(
            LibraryUiState(
                LibraryKind.Albums,
                LibrarySort.RecentlyAdded,
                SampleData.albums,
                kinds = listOf(LibraryKind.Albums),
                single = true,
            ),
        )
    }

    /** Rows; none plays, as the music comes from an album. */
    @Test fun playlists() = snap("library_playlists") {
        Library(LibraryUiState(LibraryKind.Playlists, items = LibrarySampleData.playlists))
    }
}

@Composable
private fun Library(state: LibraryUiState) = LibraryScreen(state, {}, {}, {}, {}, {}, {})
