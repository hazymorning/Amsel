package io.github.hazymorning.amsel.sample

import io.github.hazymorning.amsel.api.DjHost
import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.QueueItemUi
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.TrackUi

object PlayerSample {
    /** Shuffle on, repeat off: one control in moss, its neighbour plain. */
    val nowPlaying = SampleData.nowPlaying.copy(shuffle = true, repeat = RepeatMode.Off)

    /** Paused on the phone, which has no volume bar here, with a title that needs two lines. */
    val pausedLongTitle = NowPlayingUi(
        title = "Harbour Lights (Extended Version Recorded Live at the Old Theatre)",
        artist = "Kestrel & Finch",
        album = "Morning, Slowly",
        art = ArtRef("sample:album/8"),
        durationMs = 402_000,
        positionMs = 288_000,
        isPlaying = false,
        canSkip = false,
        speaker = SampleData.speakers[0],
        volume = null,
        artistKey = "library://artist/2",
        key = "library://track/31",
    )

    /** Moby-Dick on the kitchen speaker, four minutes into The Spouter-Inn. */
    val book = NowPlayingUi(
        title = "Moby-Dick",
        artist = "Herman Melville",
        art = ArtRef("sample:audiobook/3"),
        durationMs = (24 * 3600 + 49 * 60) * 1000L,
        positionMs = SpokenSample.mobyDickAtMs,
        isPlaying = true,
        speaker = SampleData.speakers[1],
        volume = 40,
        artistKey = "library://artist/31",
        key = "library://audiobook/3",
        kind = MediaKind.Audiobook,
        chapters = SpokenSample.chapters,
    )

    private val more = listOf(
        TrackUi("library://track/21", "Paper Moons", "Ines Okafor", "Paper Moons", ArtRef("sample:album/6"), 241),
        TrackUi("library://track/22", "Undertow", "The Lantern Choir", "Undertow", ArtRef("sample:album/7"), 316),
        TrackUi(
            "library://track/23",
            "Morning, Slowly",
            "Kestrel & Finch",
            "Morning, Slowly",
            ArtRef("sample:album/8"),
            188,
        ),
        TrackUi("library://track/24", "Saltwater", "Ines Okafor", "Saltwater", ArtRef("sample:album/3"), 207),
    )

    /** One played song, the current one, then what comes next. */
    val upNext: List<QueueItemUi> = (listOf(SampleData.tracks[5]) + SampleData.tracks.take(5) + more)
        .mapIndexed { i, t -> QueueItemUi("q$i", t, isCurrent = i == 1) }

    val speakers: List<SpeakerUi> = SampleData.speakers

    /** The DJ's hosts as its plugin names them out of the box. */
    val djHosts = listOf(
        DjHost("minimal_dj", "Minimal DJ"),
        DjHost("morning_show", "Morning show"),
        DjHost("music_nerd", "Music nerd"),
        DjHost("party_host", "Party host"),
    )

    /** What the DJ says between two songs, under the server's name for it. */
    val djWords = TrackUi(
        "ai_radio://sound_effect/dj_005",
        "Artist fact + Transition",
        artist = "",
        art = ArtRef("sample:podcast/2"),
        durationSec = 14,
        djBreak = true,
    )

    /** [upNext] with the DJ talking after the song that plays. */
    val upNextWithDj: List<QueueItemUi> = upNext.take(2) + QueueItemUi("dj", djWords) + upNext.drop(2)

    /** The DJ talks on the kitchen speaker. */
    val djTalking = nowPlaying.copy(
        title = djWords.title,
        artist = "",
        album = null,
        art = djWords.art,
        durationMs = 14_000,
        positionMs = 5_000,
        albumKey = null,
        artistKey = null,
        key = djWords.key,
        djBreak = true,
    )
}
