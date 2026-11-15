package io.github.hazymorning.amsel.sample

import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi

/** Longer lists for the Library screen: a full A–Z artist list and a realistic set of playlists. */
object LibrarySampleData {
    private fun art(key: String) = ArtRef("sample:$key")

    private fun albums(i: Int) = List(4) { art("album/${(i + it) % 11 + 1}") }.let { it.first().copy(quarters = it) }

    /** In A–Z order by sort name (a leading "The" is ignored, as Music Assistant does). */
    private val artistNames = listOf(
        "4AM Radio", "Ada Lindqvist", "Aino Rautio", "Anouk Meyer", "Basil Grant", "Bea Morrow",
        "The Bellwethers", "Cass Delaney", "Clara Voss", "Dorothea Hale", "Dunmore", "Edie Sandoval",
        "Élodie Marchand", "Fenna Brouwer", "Greta Holm", "The Gull Street Band", "Hana Ito", "Hollis",
        "Ines Okafor", "Isak Berg", "Jun Takeda", "Kestrel & Finch", "Kofi Mensah", "The Lantern Choir",
        "Lio Barros", "Marlene Vogt", "Mira Solberg", "Nadia Rahimi", "Noor", "Oskar Lund",
        "Paloma Reyes", "Pip & the Paper Boats", "Rosa Kettle", "Saoirse Quinn", "Sven Ahlgren",
        "Tove Lind", "Vera Nakamura", "Wren Callow", "Yusuf Demir", "Zadie Moss",
    )

    val artistsAToZ = artistNames.mapIndexed { i, name ->
        // Every seventh artist has no photo: one in two wears four of its albums, the other its initials.
        val photo = when {
            i % 14 == 3 -> null
            i % 7 == 3 -> albums(i)
            else -> art("artist/az$i")
        }
        MediaUi("library://artist/az$i", MediaKind.Artist, name, art = photo)
    }

    fun sortName(title: String): String = title.removePrefix("The ")

    val playlists = SampleData.playlists + listOf(
        MediaUi("library://playlist/5", MediaKind.Playlist, "Rainy Afternoons", "37 songs", art("playlist/5")),
        MediaUi("library://playlist/6", MediaKind.Playlist, "Kitchen Disco", "52 songs", art("playlist/6")),
        MediaUi("library://playlist/7", MediaKind.Playlist, "Bedtime", "12 songs", art("playlist/7")),
        MediaUi(
            "library://playlist/8",
            MediaKind.Playlist,
            "Everything We Played at the Summer Party and a Few More",
            "118 songs",
            art("playlist/8"),
        ),
        MediaUi("library://playlist/9", MediaKind.Playlist, "Running", "29 songs", null),
        MediaUi("library://playlist/10", MediaKind.Playlist, "Road Trip 2026", "74 songs", art("playlist/10")),
    )
}
