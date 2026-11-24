package io.github.hazymorning.amsel.ui.screens.library

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.navigation.OrderedToggles
import kotlinx.serialization.Serializable

/** Declaration order is the default: [Downloads] first, so offline it is in view, not scrolled off the chips. */
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
        val Default: OrderedToggles<LibraryKind> = OrderedToggles(entries, entries.toSet())

        fun arrangement(tokens: List<String>?): OrderedToggles<LibraryKind> {
            val stored = OrderedToggles.of(tokens, entries, LibraryKind::id, Default)
            return if (stored.shown.isEmpty()) stored.copy(shown = setOf(stored.order.first())) else stored
        }
    }
}

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

/** Playlists, albums and artists always count: their empty page says where music is added. */
fun LibraryKind.isHeld(held: Set<MediaType>, downloads: Boolean): Boolean = when (this) {
    LibraryKind.Downloads -> downloads
    LibraryKind.Audiobooks, LibraryKind.Podcasts, LibraryKind.Radio -> type in held
    else -> true
}

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
