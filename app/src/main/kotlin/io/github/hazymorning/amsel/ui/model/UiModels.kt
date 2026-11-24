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
 * [base] is a proxy URL without a size. [mosaic]: a smart playlist's grid of album covers, of which only
 * the top left corner shows. [quarters]: four album covers drawn two by two, for an artist without a picture.
 */
@Immutable
data class ArtRef(val base: String, val mosaic: Boolean = false, val quarters: List<ArtRef> = emptyList()) {
    fun sized(px: Int): String = if (base.startsWith("file:")) base else ImageUrls.sized(base, px)
}

/** Radio, Track and Episode have no page: a tap plays them. */
enum class MediaKind { Album, Artist, Playlist, Track, Radio, Audiobook, Podcast, Episode }

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

/** [key] is the item's uri. */
@Immutable
data class MediaUi(
    val key: String,
    val kind: MediaKind,
    val title: String,
    val subtitle: String? = null,
    val art: ArtRef? = null,
    val favorite: Boolean = false,
    /** 0 to 1, for a book or episode started. */
    val progress: Float? = null,
    val remainingSec: Int? = null,
    val resumeMs: Long? = null,
    /** An episode's podcast. */
    val parentKey: String? = null,
    val fullyPlayed: Boolean = false,
    /** An album's first artist, or a book's author. */
    val artistKey: String? = null,
    /** A book or episode from Audiobookshelf, the only source downloads can come from. */
    val downloadable: Boolean = false,
    /** A playlist the server fills anew each time it plays: already a mix, so no endless mix is made of it. */
    val dynamic: Boolean = false,
)

/** One row with a second line gives the whole list two lines, so the rows stay alike. */
fun List<MediaUi>.twoLines(): Boolean = any { !it.subtitle.isNullOrBlank() }

@Composable
@ReadOnlyComposable
fun MediaUi.secondLine(): String = subtitle?.takeIf { it.isNotBlank() } ?: LocalResources.current.getString(kind.word)

/**
 * [number] counts from 1 as the list shows it; [position] is the `start_item` that starts the item
 * here (it may count from 0), null when none does.
 */
@Immutable
data class ChapterUi(val number: Int, val title: String, val startMs: Long, val endMs: Long, val position: String?) {
    val durationSec: Int get() = ((endMs - startMs).coerceAtLeast(0) / 1000).toInt()
}

/** [newEpisodes]: the server keeps a newest-episodes row, 2.11 on; see docs/API-NOTES.md, "Audiobooks and podcasts". */
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
    val djBreak: Boolean = false,
)

@Immutable
data class SpeakerUi(
    val id: String,
    val name: String,
    val isPhone: Boolean = false,
    val song: String? = null,
    val artist: String? = null,
    val isPlaying: Boolean = false,
    val available: Boolean = true,
    /** The speakers playing in a group with it, itself not counted. */
    val others: Int = 0,
    /** 0 to 100; null for This phone and for a speaker without volume control. */
    val volume: Int? = null,
)

/** A grouped speaker keeps the speaker glyph and is named "Kitchen +2"; the link glyph is Group's alone. */
@get:DrawableRes
val SpeakerUi.glyph: Int
    get() = if (isPhone) R.drawable.ic_phone else R.drawable.ic_speaker

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
    /** At [positionAtMs], a SystemClock.elapsedRealtime time; the UI extrapolates while it [runs]. */
    val positionMs: Long = 0,
    val positionAtMs: Long = 0,
    val isPlaying: Boolean = false,
    /** Shown as playing while a seek or a play is under way: the position holds until the speaker reports. */
    val arriving: Boolean = false,
    /** Previous and next together: Music Assistant allows both or neither. */
    val canSkip: Boolean = true,
    val canSeek: Boolean = true,
    val shuffle: Boolean = false,
    /** A mix the server keeps filling always shuffles, so it is false there; see docs/API-NOTES.md, "is_dynamic". */
    val canShuffle: Boolean = true,
    val repeat: RepeatMode = RepeatMode.Off,
    val speaker: SpeakerUi,
    /** 0..100, or null when the speaker has no volume control. */
    val volume: Int? = null,
    val albumKey: String? = null,
    val artistKey: String? = null,
    /** Null until the server names what plays. */
    val key: String? = null,
    /** [MediaKind.Track], [MediaKind.Radio], [MediaKind.Audiobook] or [MediaKind.Episode]. */
    val kind: MediaKind = MediaKind.Track,
    val favorite: Boolean = false,
    /** The playback speed set in Music Assistant, at which the position moves. */
    val speed: Float = 1f,
    val chapters: List<ChapterUi> = emptyList(),
    /** An episode's podcast. */
    val parentKey: String? = null,
    val fullyPlayed: Boolean = false,
    /** The playing endless mix's seed ("Blue Hour"), blank when unnamed; null when no mix plays. */
    val mix: String? = null,
    val djBreak: Boolean = false,
) {
    val runs: Boolean get() = isPlaying && !arriving

    val spoken: Boolean get() = kind == MediaKind.Audiobook || kind == MediaKind.Episode

    val likeable: Boolean get() = !spoken && !djBreak

    /** One chapter would be the whole item. */
    val hasChapters: Boolean get() = chapters.size >= 2

    @get:StringRes
    val nextLabel: Int get() = if (hasChapters) R.string.player_next_chapter else R.string.next

    @get:StringRes
    val previousLabel: Int get() = if (hasChapters) R.string.player_previous_chapter else R.string.previous

    /** Null where the heart is hidden: no write access, a book or episode, the DJ, nothing named yet. */
    fun heart(canWrite: Boolean): Boolean? = favorite.takeIf { canWrite && likeable && key != null }
}

@Composable
@ReadOnlyComposable
fun NowPlayingUi.byline(): String {
    val resources = LocalResources.current
    if (djBreak) return resources.getString(R.string.dj)
    return artist.ifBlank { album.orEmpty() }.ifBlank {
        resources.getString(if (key == null) R.string.player_loading else kind.word)
    }
}

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

/** [nowMs] is a SystemClock.elapsedRealtime time, as [NowPlayingUi.positionAtMs]. */
fun NowPlayingUi.positionAt(nowMs: Long): Long =
    positionAt(positionMs, positionAtMs, nowMs, runs, speed.toDouble(), durationMs)

const val DOT_SEPARATOR = " · "

enum class DownloadMark { Coming, Done }

enum class ServerStatus { Connected, Connecting, Offline }
