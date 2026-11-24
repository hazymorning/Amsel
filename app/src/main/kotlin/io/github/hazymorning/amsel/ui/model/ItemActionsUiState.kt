package io.github.hazymorning.amsel.ui.model

import androidx.compose.runtime.Immutable
import io.github.hazymorning.amsel.api.ItemUri
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.isEndlessMix
import io.github.hazymorning.amsel.data.ServerExtras
import io.github.hazymorning.amsel.data.downloads.DownloadState

enum class ItemAction {
    Play,
    Shuffle,
    PlayNext,
    AddToUpNext,
    EndlessMix,
    AddToFavorites,
    RemoveFromFavorites,
    AddToPlaylist,
    GoToArtist,
    GoToAlbum,
    SimilarSongs,
    RemoveFromUpNext,
    RemoveFromPlaylist,
    PinToHome,
    UnpinFromHome,
    MarkFinished,
    MarkUnfinished,
    MarkPlayed,
    MarkUnplayed,
    GoToAuthor,
    GoToAudiobook,
    GoToPodcast,
    Bookmarks,
    Download,
    CancelDownload,
    RemoveDownload,
    SleepTimer,
    TransferPlayback,
    Dj,
}

enum class ActionsContext { Library, UpNext, EditablePlaylist, NowPlaying, Page }

fun ownPage(own: Boolean): ActionsContext = if (own) ActionsContext.Page else ActionsContext.Library

@Immutable
data class ItemActionsUiState(
    val title: String,
    val subtitle: String? = null,
    val art: ArtRef? = null,
    val kind: MediaKind = MediaKind.Track,
    val actions: List<ItemAction> = emptyList(),
    val djBreak: Boolean = false,
) {
    companion object {
        /**
         * In Up next, Remove from Up next replaces Add to Up next, which would repeat the row. [dj]: the
         * playing speaker has DJ hosts to choose from. A DJ break is no song: it keeps only Up next's
         * removal and Now Playing's speaker rows.
         */
        fun forTrack(
            track: TrackUi,
            context: ActionsContext = ActionsContext.Library,
            extras: ServerExtras = ServerExtras(),
            dynamicQueue: Boolean = false,
            dj: Boolean = false,
        ) = ItemActionsUiState(
            title = track.title,
            subtitle = listOfNotNull(track.artist, track.album).joinToString(DOT_SEPARATOR),
            art = track.art,
            kind = MediaKind.Track,
            djBreak = track.djBreak,
            actions = buildList {
                if (context == ActionsContext.NowPlaying) addAll(if (dj) PLAYBACK + ItemAction.Dj else PLAYBACK)
                if (track.djBreak) {
                    if (context == ActionsContext.UpNext) add(ItemAction.RemoveFromUpNext)
                    return@buildList
                }
                // In Up next, Play next moves the row, which any queue allows.
                if (context == ActionsContext.UpNext) {
                    add(ItemAction.PlayNext)
                } else {
                    addAll(queueActions(track.key, dynamicQueue))
                }
                if (extras.mix && seedsMix(track.key)) add(ItemAction.EndlessMix)
                add(if (track.favorite) ItemAction.RemoveFromFavorites else ItemAction.AddToFavorites)
                add(ItemAction.AddToPlaylist)
                if (track.artistKey != null) add(ItemAction.GoToArtist)
                if (track.albumKey != null) add(ItemAction.GoToAlbum)
                if (extras.similarSongs && isSong(track.key)) add(ItemAction.SimilarSongs)
                if (context == ActionsContext.UpNext) add(ItemAction.RemoveFromUpNext)
                if (context == ActionsContext.EditablePlaylist) add(ItemAction.RemoveFromPlaylist)
            },
        )

        /** [pinned] null leaves out both Pin to Home and Unpin from Home. */
        fun forMedia(
            item: MediaUi,
            pinned: Boolean? = null,
            context: ActionsContext = ActionsContext.Library,
            download: DownloadState? = null,
            extras: ServerExtras = ServerExtras(),
            dynamicQueue: Boolean = false,
        ) = ItemActionsUiState(
            title = item.title,
            subtitle = item.subtitle,
            art = item.art,
            kind = item.kind,
            actions = buildList {
                val nowPlaying = context == ActionsContext.NowPlaying
                if (nowPlaying) addAll(PLAYBACK)
                // In a list a tap opens the page, so the sheet offers Play and Shuffle; a page has its own.
                if (context == ActionsContext.Library && item.kind in STARTED_WHOLE) {
                    add(ItemAction.Play)
                    add(ItemAction.Shuffle)
                }
                if (item.kind != MediaKind.Audiobook && item.kind != MediaKind.Podcast) {
                    addAll(queueActions(item.key, dynamicQueue))
                }
                if (extras.mix && !item.dynamic && seedsMix(item.key)) add(ItemAction.EndlessMix)
                when (item.kind) {
                    MediaKind.Audiobook ->
                        add(if (item.fullyPlayed) ItemAction.MarkUnfinished else ItemAction.MarkFinished)

                    MediaKind.Episode -> add(
                        if (item.fullyPlayed) ItemAction.MarkUnplayed else ItemAction.MarkPlayed,
                    )

                    else -> Unit
                }
                if (item.kind == MediaKind.Audiobook ||
                    item.kind == MediaKind.Episode
                ) {
                    addAll(downloadActions(item, download))
                }
                if (item.kind != MediaKind.Episode) {
                    add(if (item.favorite) ItemAction.RemoveFromFavorites else ItemAction.AddToFavorites)
                }
                if (item.kind == MediaKind.Album) {
                    add(ItemAction.AddToPlaylist)
                    if (item.artistKey != null) add(ItemAction.GoToArtist)
                }
                if (item.kind == MediaKind.Audiobook) {
                    if (nowPlaying) add(ItemAction.GoToAudiobook)
                    if (item.artistKey != null) add(ItemAction.GoToAuthor)
                }
                if (item.kind == MediaKind.Episode && item.parentKey != null) add(ItemAction.GoToPodcast)
                if (extras.similarSongs && isSong(item.key)) add(ItemAction.SimilarSongs)
                if (item.kind in SPOKEN) add(ItemAction.Bookmarks)
                if (pinned != null) add(if (pinned) ItemAction.UnpinFromHome else ItemAction.PinToHome)
            },
        )
    }
}

private fun downloadActions(item: MediaUi, download: DownloadState?): List<ItemAction> = when (download) {
    null -> listOfNotNull(ItemAction.Download.takeIf { item.downloadable })
    is DownloadState.Failed -> listOf(ItemAction.Download, ItemAction.RemoveDownload)
    DownloadState.Done -> listOf(ItemAction.RemoveDownload)
    is DownloadState.Waiting, is DownloadState.Running -> listOf(ItemAction.CancelDownload)
}

/**
 * A mix the server keeps filling ([dynamicQueue]) inserts only a song as Play next; anything else
 * becomes one more source of the mix. See docs/API-NOTES.md, "Play next and Add to Up next in a mix".
 */
private fun queueActions(key: String, dynamicQueue: Boolean): List<ItemAction> = when {
    !dynamicQueue -> listOf(ItemAction.PlayNext, ItemAction.AddToUpNext)
    isSong(key) -> listOf(ItemAction.PlayNext)
    else -> emptyList()
}

/**
 * By the uri's type, not [MediaKind], which shows unlisted types (a stream, a DJ break) as Track; an
 * Up next row without a server item has a queue item id, not a uri.
 */
internal fun isSong(key: String): Boolean = ItemUri.parse(key)?.mediaType == MediaType.Track.api

/** A mix's own uri names a playlist, but a mix seeds no mix of its own. */
private fun seedsMix(key: String): Boolean = ItemUri.parse(key)?.mediaType in MIX_SEEDS && !isEndlessMix(key)

private val MIX_SEEDS = setOf(MediaType.Track, MediaType.Album, MediaType.Artist, MediaType.Playlist).map { it.api }

private val PLAYBACK = listOf(ItemAction.SleepTimer, ItemAction.TransferPlayback)

private val SPOKEN = setOf(MediaKind.Audiobook, MediaKind.Episode, MediaKind.Podcast)

/** Played as a whole and shuffled; a song or a station plays at a tap. */
private val STARTED_WHOLE = setOf(MediaKind.Album, MediaKind.Playlist, MediaKind.Artist)
