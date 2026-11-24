package io.github.hazymorning.amsel.sample

import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.TrackUi

/** `sample:` art comes from SampleCovers, which ScreenshotTest installs. */
object SampleData {
    private fun art(key: String) = ArtRef("sample:$key")

    val albums = listOf(
        MediaUi("library://album/1", MediaKind.Album, "Blue Hour", "Marlene Vogt", art("album/1")),
        MediaUi("library://album/2", MediaKind.Album, "Field Recordings, Vol. 2", "The Lantern Choir", art("album/2")),
        MediaUi("library://album/3", MediaKind.Album, "Saltwater", "Ines Okafor", art("album/3"), favorite = true),
        MediaUi("library://album/4", MediaKind.Album, "Night Trains", "Marlene Vogt", art("album/4")),
        MediaUi(
            "library://album/5",
            MediaKind.Album,
            "A Very Long Album Title That Will Not Fit On One Line",
            "Kestrel & Finch",
            art("album/5"),
        ),
        MediaUi("library://album/6", MediaKind.Album, "Paper Moons", "Ines Okafor", art("album/6")),
        MediaUi("library://album/7", MediaKind.Album, "Undertow", "The Lantern Choir", art("album/7")),
        MediaUi("library://album/8", MediaKind.Album, "Morning, Slowly", "Kestrel & Finch", art("album/8")),
    )

    val artists = listOf(
        MediaUi("library://artist/1", MediaKind.Artist, "Ines Okafor", art = art("artist/1")),
        MediaUi("library://artist/2", MediaKind.Artist, "Kestrel & Finch", art = art("artist/2")),
        MediaUi("library://artist/3", MediaKind.Artist, "Marlene Vogt", art = art("artist/3")),
        MediaUi("library://artist/4", MediaKind.Artist, "The Lantern Choir", art = art("artist/4")),
    )

    val playlists = listOf(
        MediaUi(
            "library://playlist/1",
            MediaKind.Playlist,
            "Sunday Morning",
            "9 songs",
            art("playlist/1"),
            favorite = true,
        ),
        MediaUi("library://playlist/2", MediaKind.Playlist, "Cooking", "41 songs", art("playlist/2"), favorite = true),
        MediaUi("library://playlist/3", MediaKind.Playlist, "Long Drive", "63 songs", art("playlist/3")),
        MediaUi("library://playlist/4", MediaKind.Playlist, "Focus", "18 songs", art("playlist/4")),
    )

    val jumpBackIn = listOf(albums[0], playlists[0], artists[1], albums[2], playlists[1], albums[3])

    val tracks = listOf(
        TrackUi("library://track/1", "Lanterns", "Marlene Vogt", "Blue Hour", art("album/1"), 214, 1),
        TrackUi(
            "library://track/2",
            "The Quiet Part",
            "Marlene Vogt",
            "Blue Hour",
            art("album/1"),
            187,
            2,
            favorite = true,
        ),
        TrackUi(
            "library://track/3",
            "Harbour Lights (Extended Version Recorded Live at the Old Theatre)",
            "Marlene Vogt",
            "Blue Hour",
            art("album/1"),
            402,
            3,
        ),
        TrackUi("library://track/4", "Slow Tide", "Marlene Vogt", "Blue Hour", art("album/1"), 255, 4),
        TrackUi("library://track/5", "Northbound", "Marlene Vogt", "Blue Hour", art("album/1"), 198, 5),
        TrackUi("library://track/6", "Blue Hour", "Marlene Vogt", "Blue Hour", art("album/1"), 301, 6),
    )

    val speakers = listOf(
        SpeakerUi("phone", "This phone", isPhone = true),
        SpeakerUi("kitchen", "Kitchen", song = "Lanterns", artist = "Marlene Vogt", isPlaying = true),
        SpeakerUi("living", "Living room"),
        SpeakerUi("bedroom", "Bedroom", song = "Cooking", artist = "Playlist", isPlaying = false),
        SpeakerUi("garden", "Garden", available = false),
    )

    val nowPlaying = NowPlayingUi(
        title = "Lanterns",
        artist = "Marlene Vogt",
        album = "Blue Hour",
        art = art("album/1"),
        durationMs = 214_000,
        positionMs = 71_000,
        isPlaying = true,
        speaker = speakers[1],
        volume = 40,
        albumKey = "library://album/1",
        artistKey = "library://artist/3",
        key = "library://track/1",
    )
}
