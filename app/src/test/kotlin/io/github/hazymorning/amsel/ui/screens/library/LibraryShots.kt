package io.github.hazymorning.amsel.ui.screens.library

import androidx.compose.runtime.Composable
import io.github.hazymorning.amsel.api.LibrarySort
import io.github.hazymorning.amsel.sample.LibrarySampleData
import io.github.hazymorning.amsel.sample.SampleData
import io.github.hazymorning.amsel.testing.ScreenshotTest
import io.github.hazymorning.amsel.testing.ShotVariant
import org.junit.Test

class LibraryShots : ScreenshotTest() {
    @Test fun albums() = snap("library_albums", listOf(ShotVariant.LargeFont)) {
        val playing = setOf(SampleData.albums.first().key)
        Library(LibraryUiState(LibraryKind.Albums, LibrarySort.RecentlyAdded, SampleData.albums, playing = playing))
    }

    @Test fun empty() = snap("library_empty") { Library(LibraryUiState(LibraryKind.Albums)) }

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

    @Test fun playlists() = snap("library_playlists") {
        Library(LibraryUiState(LibraryKind.Playlists, items = LibrarySampleData.playlists))
    }
}

@Composable
private fun Library(state: LibraryUiState) = LibraryScreen(state, {}, {}, {}, {}, {}, {})
