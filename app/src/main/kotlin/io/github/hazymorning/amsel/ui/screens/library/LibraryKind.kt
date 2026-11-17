package io.github.hazymorning.amsel.ui.screens.library

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.navigation.Arrangement
import kotlinx.serialization.Serializable

/**
 * What the Library can show, each as a chip, in the person's order (Settings, Library). Downloads
 * first as it comes, in sight without a connection, where the row would hide it.
 */
@Serializable
enum class LibraryKind(val id: String) {
    Downloads("downloads"),
    Playlists("playlists"),
    Albums("albums"),
    Artists("artists"),
    Radio("radio"),
    Audiobooks("audiobooks"),
    Podcasts("podcasts"),
    ;

    companion object {
        /** The Library as it comes: every kind, in this order. */
        val Default: Arrangement<LibraryKind> = Arrangement(entries, entries.toSet())

        /** The kinds in the person's order, one at least among those shown. */
        fun arrangement(tokens: List<String>?): Arrangement<LibraryKind> {
            val stored = Arrangement.of(tokens, entries, LibraryKind::id, Default)
            return if (stored.shown.isEmpty()) stored.copy(shown = setOf(stored.order.first())) else stored
        }
    }
}

/** The kind in the library index; none for Downloads, which are on the phone. */
internal val LibraryKind.type: MediaType?
    get() = when (this) {
        LibraryKind.Playlists -> MediaType.Playlist
        LibraryKind.Albums -> MediaType.Album
        LibraryKind.Artists -> MediaType.Artist
        LibraryKind.Radio -> MediaType.Radio
        LibraryKind.Audiobooks -> MediaType.Audiobook
        LibraryKind.Podcasts -> MediaType.Podcast
        LibraryKind.Downloads -> null
    }

@get:DrawableRes
val LibraryKind.icon: Int
    get() = when (this) {
        LibraryKind.Playlists -> R.drawable.ic_playlist
        LibraryKind.Albums -> R.drawable.ic_album
        LibraryKind.Artists -> R.drawable.ic_artist
        LibraryKind.Radio -> R.drawable.ic_radio
        LibraryKind.Audiobooks -> R.drawable.ic_audiobook
        LibraryKind.Podcasts -> R.drawable.ic_podcast
        LibraryKind.Downloads -> R.drawable.ic_download
    }

/**
 * Whether the Library has anything of [this] kind to show: books, podcasts and stations while the
 * index holds some ([held]), Downloads while the phone does; playlists, albums and artists always,
 * their empty page saying where music is added.
 */
fun LibraryKind.isHeld(held: Set<MediaType>, downloads: Boolean): Boolean = when (this) {
    LibraryKind.Downloads -> downloads
    LibraryKind.Audiobooks, LibraryKind.Podcasts, LibraryKind.Radio -> type in held
    else -> true
}

/**
 * The chips: the kinds the person shows ([arranged], in their order) that the Library has anything
 * of; while it has none of them, every one arranged, each saying it is empty.
 */
fun libraryKinds(arranged: List<LibraryKind>, held: Set<MediaType>, downloads: Boolean = false): List<LibraryKind> =
    arranged.filter { it.isHeld(held, downloads) }.ifEmpty { arranged }

@get:StringRes
val LibraryKind.label: Int
    get() = when (this) {
        LibraryKind.Playlists -> PlaybackR.string.playlists
        LibraryKind.Albums -> PlaybackR.string.albums
        LibraryKind.Artists -> PlaybackR.string.artists
        LibraryKind.Audiobooks -> PlaybackR.string.audiobooks
        LibraryKind.Podcasts -> PlaybackR.string.podcasts
        LibraryKind.Radio -> R.string.radio
        LibraryKind.Downloads -> R.string.downloads
    }
