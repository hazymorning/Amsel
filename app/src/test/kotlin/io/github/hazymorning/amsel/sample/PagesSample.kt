package io.github.hazymorning.amsel.sample

import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import io.github.hazymorning.amsel.ui.screens.pages.AlbumUi
import io.github.hazymorning.amsel.ui.screens.pages.AlbumUiState
import io.github.hazymorning.amsel.ui.screens.pages.ArtistUiState
import io.github.hazymorning.amsel.ui.screens.pages.PlaylistUi
import io.github.hazymorning.amsel.ui.screens.pages.PlaylistUiState
import io.github.hazymorning.amsel.ui.screens.pages.SimilarSongsUiState

object PagesSample {
    private fun art(key: String) = ArtRef("sample:$key")

    private const val MARLENE = "Marlene Vogt"
    private const val MARLENE_KEY = "library://artist/3"

    private fun blueHour(n: Int, title: String, sec: Int) =
        TrackUi("library://track/$n", title, MARLENE, "Blue Hour", art("album/1"), sec, n, artistKey = MARLENE_KEY)

    /** "Blue Hour": the shared sample tracks plus four more, ten in all; one has a guest. */
    val blueHourTracks: List<TrackUi> = SampleData.tracks + listOf(
        blueHour(7, "Glasshouse", 236),
        blueHour(8, "What the River Said", 274).copy(artist = "$MARLENE, Ines Okafor"),
        blueHour(9, "Small Hours", 193),
        blueHour(10, "Coda", 142),
    )

    private val marleneAlbums = listOf(
        SampleData.albums[3].copy(subtitle = "2017"),
        MediaUi("library://album/9", MediaKind.Album, "Weather Patterns", "2016", art("album/9")),
        MediaUi("library://album/10", MediaKind.Album, "Live at the Old Theatre", "2021", art("album/10")),
        MediaUi("library://album/11", MediaKind.Album, "Early Songs", "2012", art("album/11")),
    )

    val album = AlbumUiState(
        album = AlbumUi(
            key = "library://album/1",
            title = "Blue Hour",
            art = art("album/1"),
            artist = MARLENE,
            artistKey = MARLENE_KEY,
            year = 2019,
        ),
        tracks = blueHourTracks,
        moreByArtist = marleneAlbums,
        currentTrackKey = "library://track/1",
    )

    val albumUnavailable = AlbumUiState(unavailable = true)

    val playlist = PlaylistUiState(
        playlist = PlaylistUi("library://playlist/1", "Sunday Morning", art("playlist/1"), owner = "Anna"),
        complete = true,
        tracks = listOf(
            TrackUi("library://track/20", "Paper Moons", "Ines Okafor", "Paper Moons", art("album/6"), 221),
            SampleData.tracks[0],
            TrackUi("library://track/21", "Morning, Slowly", "Kestrel & Finch", "Morning, Slowly", art("album/8"), 248),
            TrackUi("library://track/22", "Salt", "Ines Okafor", "Saltwater", art("album/3"), 203),
            TrackUi("library://track/23", "Undertow", "The Lantern Choir", "Undertow", art("album/7"), 312),
            SampleData.tracks[2],
            TrackUi(
                "library://track/24",
                "A Song for the Kettle",
                "Kestrel & Finch",
                "A Very Long Album Title That Will Not Fit On One Line",
                art("album/5"),
                177,
            ),
            TrackUi("library://track/25", "Night Trains", MARLENE, "Night Trains", art("album/4"), 265),
            TrackUi(
                "library://track/26",
                "Field Notes",
                "The Lantern Choir",
                "Field Recordings, Vol. 2",
                art("album/2"),
                290,
            ),
        ),
        currentTrackKey = SampleData.nowPlaying.key,
    )

    val artist = ArtistUiState(
        artist = SampleData.artists[2],
        popular = listOf(
            SampleData.tracks[0],
            TrackUi("library://track/25", "Night Trains", MARLENE, "Night Trains", art("album/4"), 265),
            SampleData.tracks[2],
            TrackUi("library://track/30", "Weather Patterns", MARLENE, "Weather Patterns", art("album/9"), 251),
            SampleData.tracks[1],
            TrackUi("library://track/31", "Early Light", MARLENE, "Early Songs", art("album/11"), 199),
        ),
        albums = listOf(
            MediaUi("library://album/1", MediaKind.Album, "Blue Hour", "2019", art("album/1")),
        ) + marleneAlbums,
        // The second is a music service's artist that has no picture.
        similar = listOf(
            SampleData.artists[0],
            MediaUi("streaming://artist/7", MediaKind.Artist, "Odile Brandt"),
            SampleData.artists[3],
            SampleData.artists[1],
        ),
        currentTrackKey = "library://track/1",
    )

    private fun similar(n: Int, title: String, artist: String, album: String?, cover: Int) =
        TrackUi("streaming://track/$n", title, artist, album, art("album/$cover"), 200 + n)

    /** Similar to "Lanterns": a music service's own songs, the second one playing. */
    val similarSongs = SimilarSongsUiState(
        songs = listOf(
            similar(1, "Paper Moons", "Ines Okafor", "Paper Moons", 6),
            similar(2, "Morning, Slowly", "Kestrel & Finch", "Morning, Slowly", 8),
            similar(3, "Undertow", "The Lantern Choir", "Undertow", 7),
            similar(4, "Night Trains", MARLENE, "Night Trains", 4),
            similar(5, "A Song for the Kettle", "Kestrel & Finch", "A Very Long Album Title That Will Not Fit", 5),
            similar(6, "Salt", "Ines Okafor", "Saltwater", 3),
            similar(7, "Field Notes", "The Lantern Choir", "Field Recordings, Vol. 2", 2),
            similar(8, "Harbour Lights", "Odile Brandt", null, 9),
            similar(9, "Glass Weather", "Ines Okafor", "Saltwater", 3),
            similar(10, "Low Sun", "Kestrel & Finch", "Morning, Slowly", 8),
            similar(11, "The Long Way Round", "The Lantern Choir", "Undertow", 7),
            similar(12, "Early Light", MARLENE, "Early Songs", 11),
            similar(13, "Tidewater", "Odile Brandt", "Tidewater", 10),
        ),
        currentTrackKey = "streaming://track/2",
    )
}
