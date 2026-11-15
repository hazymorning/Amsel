package io.github.hazymorning.amsel.sample

import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi

/** What a search for "blue" finds in the sample library (library matches first in each section). */
object SearchSampleData {
    private fun art(key: String) = ArtRef("sample:$key")

    const val QUERY = "blue"

    val topAlbum = SampleData.albums[0]

    val songs = listOf(
        TrackUi("library://track/6", "Blue Hour", "Marlene Vogt", "Blue Hour", art("album/1"), 301),
        TrackUi("library://track/21", "Into the Blue", "Ines Okafor", "Saltwater", art("album/3"), 244),
        TrackUi(
            "library://track/22",
            "Blue in the Harbour, Where the Old Boats Sleep (Live)",
            "The Lantern Choir",
            "Undertow",
            art("album/7"),
            389,
        ),
        TrackUi("spotify://track/23", "Bluebird", "Kestrel & Finch", "Morning, Slowly", art("album/8"), 176),
    )

    val albums = listOf(
        MediaUi("library://album/9", MediaKind.Album, "Blue Light Sessions", "Kestrel & Finch", art("album/9")),
        MediaUi("spotify://album/10", MediaKind.Album, "Deep Blue", "The Lantern Choir", art("album/10")),
    )

    val artists = listOf(
        MediaUi("spotify://artist/11", MediaKind.Artist, "The Blue Rooms", art = art("artist/11")),
    )

    val playlists = listOf(
        MediaUi("library://playlist/12", MediaKind.Playlist, "Blue Mornings", "32 songs", art("playlist/12")),
    )

    val radio = listOf(
        MediaUi("library://radio/13", MediaKind.Radio, "Blue Note Radio", art = art("radio/13")),
        MediaUi("tunein://radio/14", MediaKind.Radio, "Bluegrass Country"),
    )
}
