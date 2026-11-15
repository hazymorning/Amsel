package io.github.hazymorning.amsel.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.hazymorning.amsel.api.LibrarySort
import io.github.hazymorning.amsel.sample.LibrarySampleData
import io.github.hazymorning.amsel.sample.SampleData
import io.github.hazymorning.amsel.sample.SpokenSample
import io.github.hazymorning.amsel.testing.ScreenshotTest
import io.github.hazymorning.amsel.testing.ShotVariant
import io.github.hazymorning.amsel.ui.components.LocalDownloadMarks
import io.github.hazymorning.amsel.ui.model.DownloadMark
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.SpokenWord
import org.junit.Test

class LibraryShots : ScreenshotTest() {
    /** Blue Hour plays (Lanterns, in the kitchen): its ring is the only colour besides the covers. */
    @Test fun albums() = snap("library_albums", ShotVariant.entries) {
        val playing = setOf(SampleData.albums.first().key)
        Library(LibraryUiState(LibraryKind.Albums, LibrarySort.RecentlyAdded, SampleData.albums, playing = playing))
    }

    @Test fun empty() = snap("library_empty") { Library(LibraryUiState(LibraryKind.Albums)) }

    /** Books on the phone carry the filled arrow before their second line, one on its way a ring. */
    @Test fun audiobooksDownloaded() = snap(
        "library_audiobooks_downloaded",
        listOf(ShotVariant.Light, ShotVariant.Dark),
    ) {
        val books = listOf(
            SpokenSample.mobyDick,
            SpokenSample.book(4, "Middlemarch", "George Eliot"),
            SpokenSample.book(5, "Bleak House", "Charles Dickens"),
        )
        val marks = mapOf(books[0].key to DownloadMark.Done, books[1].key to DownloadMark.Coming)
        CompositionLocalProvider(LocalDownloadMarks provides remember { mutableStateOf(marks) }) {
            Library(
                LibraryUiState(
                    LibraryKind.Audiobooks,
                    items = books,
                    kinds = libraryKinds(SpokenWord(books = true), downloads = true),
                ),
            )
        }
    }

    /** What is on the phone, newest first, and what is on its way. */
    @Test fun downloads() = snap("library_downloads", ShotVariant.entries) {
        val book = SpokenSample.mobyDick
        val cover = SpokenSample.longWalk.art
        val rows = listOf(
            MediaUi("library://podcast_episode/7", MediaKind.Episode, "Over the Moor", "Downloading · 62 %", cover),
            book.copy(subtitle = "Herman Melville · 312 MB"),
            MediaUi(
                "library://podcast_episode/5",
                MediaKind.Episode,
                "Rain on the Ridge",
                "The Long Walk · 48 MB",
                cover,
            ),
        )
        Library(
            LibraryUiState(
                LibraryKind.Downloads,
                items = rows,
                kinds = libraryKinds(SpokenWord(books = true, podcasts = true), downloads = true),
            ),
        )
    }

    /** Rows; none plays, as the music comes from an album. */
    @Test fun playlists() = snap("library_playlists", listOf(ShotVariant.Light, ShotVariant.Dark)) {
        Library(LibraryUiState(LibraryKind.Playlists, items = LibrarySampleData.playlists))
    }
}

@Composable
private fun Library(state: LibraryUiState) = LibraryScreen(state, {}, {}, {}, {}, {}, {})
