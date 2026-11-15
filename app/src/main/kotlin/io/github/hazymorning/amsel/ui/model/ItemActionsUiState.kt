package io.github.hazymorning.amsel.ui.model

import androidx.compose.runtime.Immutable
import io.github.hazymorning.amsel.api.ItemUri
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.isEndlessMix
import io.github.hazymorning.amsel.data.ServerExtras
import io.github.hazymorning.amsel.data.downloads.DownloadState

/** Everything the ⋮ sheet can offer; [ItemActionsUiState] lists them in order. */
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

/**
 * Where the sheet was opened: Up next and an editable playlist add their removal; Now Playing adds
 * the sleep timer, moving the music to another speaker, the DJ and, for a book, the way to its page.
 * On an item's own page, Play and Shuffle are the page's buttons, so the sheet leaves them out.
 */
enum class ActionsContext { Library, UpNext, EditablePlaylist, NowPlaying, Page }

/** [ActionsContext.Page] for a page's own item, [ActionsContext.Library] for one listed on it. */
fun ownPage(own: Boolean): ActionsContext = if (own) ActionsContext.Page else ActionsContext.Library

/** The ⋮ sheet for one song, album, artist or playlist: a header, then [actions] in order. */
@Immutable
data class ItemActionsUiState(
    val title: String,
    val subtitle: String? = null,
    val art: ArtRef? = null,
    val kind: MediaKind = MediaKind.Track,
    val actions: List<ItemAction> = emptyList(),
    /** The DJ's words between two songs: the header names them as Up next does, over "DJ". */
    val djBreak: Boolean = false,
) {
    companion object {
        /**
         * A song's actions. In Up next, "Add to Up next" would repeat what is already there, so it
         * gives way to "Remove from Up next"; a playlist the person can edit adds "Remove from this playlist".
         * [extras] and [dynamicQueue] as for [forMedia]. [dj]: the speaker that plays has a DJ to
         * choose, which Now Playing offers with its other speaker rows. What the DJ says between
         * two songs is no song: Up next can drop it, and Now Playing keeps only the speaker's rows.
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
                // Up next's own Play next moves the row, which any queue allows.
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

        /**
         * An album's, artist's, playlist's, station's, book's, podcast's or episode's actions. Only
         * albums go into playlists and lead to their artist, and books to their author, when known.
         * A book or episode is marked finished, or unfinished once it is, and downloaded, when it is
         * from Audiobookshelf, as far as [download] has come. [pinned] says whether it is on Home,
         * for Pin to Home or Unpin from Home; null leaves both out. [extras] is what the server has
         * beyond playing what it holds; [dynamicQueue] says the speaker that plays is in a mix the
         * server keeps filling.
         */
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
                // A tap on a cover opens its page; a long press starts it from where it is.
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

/** Download while it can be, stop it on its way, remove it once there; one that stopped is tried again or removed. */
private fun downloadActions(item: MediaUi, download: DownloadState?): List<ItemAction> = when (download) {
    null -> listOfNotNull(ItemAction.Download.takeIf { item.downloadable })
    is DownloadState.Failed -> listOf(ItemAction.Download, ItemAction.RemoveDownload)
    DownloadState.Done -> listOf(ItemAction.RemoveDownload)
    is DownloadState.Waiting, is DownloadState.Running -> listOf(ItemAction.CancelDownload)
}

/**
 * Play next and Add to Up next for [key]. A queue the server keeps filling ([dynamicQueue]) places
 * only a song that is to play next; anything else it takes as one more source of the mix and builds
 * what is coming anew, so those rows would not do what they say.
 */
private fun queueActions(key: String, dynamicQueue: Boolean): List<ItemAction> = when {
    !dynamicQueue -> listOf(ItemAction.PlayNext, ItemAction.AddToUpNext)
    isSong(key) -> listOf(ItemAction.PlayNext)
    else -> emptyList()
}

/**
 * By the uri's own type, not the kind shown: that calls everything the app does not list a song (a
 * stream, a spoken break), and a row of Up next the server names no item for has no uri at all.
 */
internal fun isSong(key: String): Boolean = ItemUri.parse(key)?.mediaType == MediaType.Track.api

/** What the server makes an endless mix of; a mix has no mix of its own. */
private fun seedsMix(key: String): Boolean = ItemUri.parse(key)?.mediaType in MIX_SEEDS && !isEndlessMix(key)

private val MIX_SEEDS = setOf(MediaType.Track, MediaType.Album, MediaType.Artist, MediaType.Playlist).map { it.api }

/** Now Playing's own: about the speaker rather than the item. The DJ joins them where there is one. */
private val PLAYBACK = listOf(ItemAction.SleepTimer, ItemAction.TransferPlayback)

/** The kinds that are listened to rather than played: they keep bookmarks. */
private val SPOKEN = setOf(MediaKind.Audiobook, MediaKind.Episode, MediaKind.Podcast)

/** What plays as a whole and can be shuffled; a song or a station plays at a tap. */
private val STARTED_WHOLE = setOf(MediaKind.Album, MediaKind.Playlist, MediaKind.Artist)
