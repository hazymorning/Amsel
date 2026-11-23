package io.github.hazymorning.amsel.ui.model

import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.ImageUrls
import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.playback.positionAt

/**
 * Artwork on the server's image proxy ([base] has no size), requested at the size it is drawn at;
 * [mosaic] for a smart playlist's grid of album covers, of which only the top left corner shows.
 * [quarters] are four album covers drawn two by two, for an artist without a picture of its own.
 */
@Immutable
data class ArtRef(val base: String, val mosaic: Boolean = false, val quarters: List<ArtRef> = emptyList()) {
    /** The server's copy at about [px]; a downloaded cover is the file on the phone, as it is. */
    fun sized(px: Int): String = if (base.startsWith("file:")) base else ImageUrls.sized(base, px)
}

/** What an item is. Radio stations, tracks and episodes have no page: tapping one plays it. */
enum class MediaKind { Album, Artist, Playlist, Track, Radio, Audiobook, Podcast, Episode }

/** The kind in a word: "Album", "Radio". */
@get:StringRes
val MediaKind.word: Int
    get() = when (this) {
        MediaKind.Album -> R.string.kind_album
        MediaKind.Artist -> R.string.kind_artist
        MediaKind.Playlist -> R.string.kind_playlist
        MediaKind.Track -> R.string.kind_song
        MediaKind.Radio -> R.string.radio
        MediaKind.Audiobook -> R.string.kind_audiobook
        MediaKind.Podcast -> R.string.kind_podcast
        MediaKind.Episode -> R.string.kind_episode
    }

/**
 * An album, artist, playlist, radio station, book, podcast or episode as shown in rows, tiles and
 * shelves. [key] is the item's URI.
 */
@Immutable
data class MediaUi(
    val key: String,
    val kind: MediaKind,
    val title: String,
    val subtitle: String? = null,
    val art: ArtRef? = null,
    val favorite: Boolean = false,
    /** How far the person is through a book or episode they started, 0 to 1. */
    val progress: Float? = null,
    /** What is left of it, in seconds. */
    val remainingSec: Int? = null,
    /** Where the person stopped in a book or episode, in milliseconds. */
    val resumeMs: Long? = null,
    /** An episode's podcast. */
    val parentKey: String? = null,
    /** A book or episode the person finished. */
    val fullyPlayed: Boolean = false,
    /** An album's first artist, or a book's author. */
    val artistKey: String? = null,
    /** A book or an episode from Audiobookshelf, which can be downloaded to the phone. */
    val downloadable: Boolean = false,
    /** A playlist the server fills anew each time it plays: a mix already, so none is made of it. */
    val dynamic: Boolean = false,
)

/** Rows that stand together stand alike: as soon as one of a list's items has a second line, all do. */
fun List<MediaUi>.twoLines(): Boolean = any { !it.subtitle.isNullOrBlank() }

/**
 * The line under an item's name among two-line rows: the server's (an artist, an owner), and where
 * it has none, what the item is ("Radio", "Playlist").
 */
@Composable
@ReadOnlyComposable
fun MediaUi.secondLine(): String = subtitle?.takeIf { it.isNotBlank() } ?: LocalResources.current.getString(kind.word)

/**
 * A chapter of a book or episode on its own timeline. [number] counts from 1 as the list shows it;
 * [position] is the `start_item` that starts the book here (it may count from 0), null when none does.
 */
@Immutable
data class ChapterUi(val number: Int, val title: String, val startMs: Long, val endMs: Long, val position: String?) {
    val durationSec: Int get() = ((endMs - startMs).coerceAtLeast(0) / 1000).toInt()
}

/**
 * The library index holds audiobooks, or podcasts; [newEpisodes]: the server also keeps a
 * newest-episodes row (2.11 on), which 2.10 has not.
 */
@Immutable
data class SpokenWord(val books: Boolean = false, val podcasts: Boolean = false, val newEpisodes: Boolean = false)

@Immutable
data class TrackUi(
    val key: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val art: ArtRef? = null,
    val durationSec: Int = 0,
    val number: Int? = null,
    val favorite: Boolean = false,
    val albumKey: String? = null,
    val artistKey: String? = null,
    /** Not a song: what the DJ says between two songs. */
    val djBreak: Boolean = false,
)

/** A speaker in the "Play on" sheet. "This phone" is a speaker too. */
@Immutable
data class SpeakerUi(
    val id: String,
    val name: String,
    val isPhone: Boolean = false,
    /** The song it plays and its artist; no song when idle. */
    val song: String? = null,
    val artist: String? = null,
    val isPlaying: Boolean = false,
    val available: Boolean = true,
    /** The speakers playing along with it, itself not counted. */
    val others: Int = 0,
    /** Its level, 0 to 100; none for This phone and for a speaker whose volume is fixed. */
    val volume: Int? = null,
)

/**
 * This phone's glyph or a speaker's, in a list of speakers. A speaker others play along with is
 * still a speaker, named so ("Kitchen +2"): the link glyph is Group's alone.
 */
@get:DrawableRes
val SpeakerUi.glyph: Int
    get() = if (isPhone) R.drawable.ic_phone else R.drawable.ic_speaker

/** What the app calls the speaker: "This phone" in the person's language, else the server's name. */
fun SpeakerUi.displayName(resources: Resources): String =
    if (isPhone) resources.getString(R.string.this_phone) else name

@Composable
@ReadOnlyComposable
fun SpeakerUi.displayName(): String = displayName(LocalResources.current)

@Immutable
data class NowPlayingUi(
    val title: String,
    val artist: String,
    val album: String? = null,
    val art: ArtRef? = null,
    val durationMs: Long = 0,
    /** Position at [positionAtMs] (SystemClock.elapsedRealtime); the UI extrapolates while it [runs]. */
    val positionMs: Long = 0,
    val positionAtMs: Long = 0,
    val isPlaying: Boolean = false,
    /**
     * It plays, as far as the buttons go, but the speaker is still on its way there (a seek, a play
     * starting): the position stands until the speaker reports it.
     */
    val arriving: Boolean = false,
    /** Previous and next: Music Assistant allows both or neither. */
    val canSkip: Boolean = true,
    val canSeek: Boolean = true,
    val shuffle: Boolean = false,
    /** Shuffle can be switched; a mix the server keeps flowing always shuffles. */
    val canShuffle: Boolean = true,
    val repeat: RepeatMode = RepeatMode.Off,
    val speaker: SpeakerUi,
    /** 0..100, or null when the speaker has no volume control. */
    val volume: Int? = null,
    val albumKey: String? = null,
    val artistKey: String? = null,
    /** The song's, station's, book's or episode's uri, what its ⋮ sheet acts on; null until the server names it. */
    val key: String? = null,
    /** [MediaKind.Track], [MediaKind.Radio], [MediaKind.Audiobook] or [MediaKind.Episode]. */
    val kind: MediaKind = MediaKind.Track,
    val favorite: Boolean = false,
    /** How fast the position moves: a book's or episode's speed set in Music Assistant. */
    val speed: Float = 1f,
    /** A book's or episode's chapters, in order; empty for music. */
    val chapters: List<ChapterUi> = emptyList(),
    /** An episode's podcast. */
    val parentKey: String? = null,
    /** A book or episode the person finished. */
    val fullyPlayed: Boolean = false,
    /** What the endless mix that plays was made from ("Blue Hour"), blank when unnamed; null when none plays. */
    val mix: String? = null,
    /** What plays is not a song but the DJ, talking between two songs. */
    val djBreak: Boolean = false,
) {
    /** The position moves on with time. */
    val runs: Boolean get() = isPlaying && !arriving

    /** A book or an episode: Now Playing skips by seconds and steps by chapter. */
    val spoken: Boolean get() = kind == MediaKind.Audiobook || kind == MediaKind.Episode

    /** A song or a station can be liked; a book, an episode and the DJ's words cannot. */
    val likeable: Boolean get() = !spoken && !djBreak

    /** Two or more chapters: Now Playing shows and steps by them; one would be the whole item. */
    val hasChapters: Boolean get() = chapters.size >= 2

    /** What next says, wherever it is offered: a chapter's step, or the next item. */
    @get:StringRes
    val nextLabel: Int get() = if (hasChapters) R.string.player_next_chapter else R.string.next

    @get:StringRes
    val previousLabel: Int get() = if (hasChapters) R.string.player_previous_chapter else R.string.previous

    /**
     * The heart beside the title, as the notification and the car show it too: liked or not, and
     * null where Now Playing leaves it out (a guest, a book or an episode, the DJ, nothing named yet).
     */
    fun heart(canWrite: Boolean): Boolean? = favorite.takeIf { canWrite && likeable && key != null }
}

/**
 * The line under the title, never empty: the artist, else the album; until the server names what
 * starts, "Loading…"; and where it names neither, what plays ("Radio", "Episode"). The DJ's words
 * stand over "DJ".
 */
@Composable
@ReadOnlyComposable
fun NowPlayingUi.byline(): String {
    val resources = LocalResources.current
    if (djBreak) return resources.getString(R.string.dj)
    return artist.ifBlank { album.orEmpty() }.ifBlank {
        resources.getString(if (key == null) R.string.player_loading else kind.word)
    }
}

/** The line above the artwork: the endless mix that plays, else the album the song is from; null when neither. */
@Composable
@ReadOnlyComposable
fun NowPlayingUi.sourceLine(): String? {
    val resources = LocalResources.current
    return when {
        mix == null -> album?.let { resources.getString(R.string.player_from, it) }
        mix.isBlank() -> resources.getString(R.string.endless_mix)
        else -> resources.getString(R.string.endless_mix_of, mix)
    }
}

@Immutable
data class QueueItemUi(val id: String, val track: TrackUi, val isCurrent: Boolean = false)

/**
 * Where playback is at [nowMs] (SystemClock.elapsedRealtime): the reported position plus the time
 * since it was reported while it runs, at the item's speed, kept within the track.
 */
fun NowPlayingUi.positionAt(nowMs: Long): Long =
    positionAt(positionMs, positionAtMs, nowMs, runs, speed.toDouble(), durationMs)

/** Between the parts of a line of details: "Album · Marlene Vogt", "12 songs · 48 min". */
const val DOT_SEPARATOR = " · "

/** What the phone holds of a book or an episode: on its way down, or there. */
enum class DownloadMark { Coming, Done }

/** Whether Music Assistant answers: connected, still connecting, or out of reach. */
enum class ServerStatus { Connected, Connecting, Offline }
