package io.github.hazymorning.amsel.playback

import android.provider.MediaStore
import io.github.hazymorning.amsel.api.ItemUri
import io.github.hazymorning.amsel.api.LibrarySort
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.MediaItemImage
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.SearchResults
import io.github.hazymorning.amsel.api.albumTracks
import io.github.hazymorning.amsel.api.artistAlbums
import io.github.hazymorning.amsel.api.libraryItems
import io.github.hazymorning.amsel.api.listening
import io.github.hazymorning.amsel.api.playlistTracks
import io.github.hazymorning.amsel.api.podcastEpisodes
import io.github.hazymorning.amsel.api.recentlyPlayed
import io.github.hazymorning.amsel.api.search
import io.github.hazymorning.amsel.api.started

/**
 * A row the car can show: an album, playlist, artist, track, book, podcast or episode. For a book
 * or an episode, [played] is how much of it is played (0 to 1); it is null for music.
 */
data class BrowseNode(
    val uri: String,
    val title: String,
    val subtitle: String?,
    val cover: MediaItemImage?,
    val browsable: Boolean,
    val mediaType: String = MediaType.Track.api,
    val played: Double? = null,
    val finished: Boolean = false,
)

/** Library reads for the car, live from the server (each wakes the socket) and capped; [holds] asks the index. */
interface Catalog {
    suspend fun recent(limit: Int): List<BrowseNode>

    /** Books and episodes the person is in the middle of, most recent first. */
    suspend fun inProgress(limit: Int): List<BrowseNode>

    /** Whether the library holds any of a tab's kind, such as "audiobook" or "podcast". */
    suspend fun holds(tab: String): Boolean

    suspend fun library(tab: String, offset: Int, limit: Int): List<BrowseNode>

    suspend fun children(parentUri: String, offset: Int, limit: Int): List<BrowseNode>

    /**
     * The uri a spoken "play [query]" means, or null when the server finds nothing. [focus] is the
     * assistant's guess at the kind (MediaStore's `EXTRA_MEDIA_FOCUS`), when it has one.
     */
    suspend fun search(query: String, focus: String?): String?
}

/**
 * The car's library: live server reads, each list capped by the caller. Each read [wake]s the
 * session, since the car browses with the phone locked and nothing playing. [indexed] says whether
 * the library index holds any of a kind.
 */
class DataCatalog(
    private val api: MaApi,
    private val wake: () -> Unit,
    private val indexed: suspend (MediaType) -> Boolean,
) : Catalog {
    override suspend fun recent(limit: Int): List<BrowseNode> {
        wake()
        return api.recentlyPlayed(limit, listOf(MediaType.Album, MediaType.Playlist, MediaType.Artist)).map {
            it.node()
        }
    }

    override suspend fun inProgress(limit: Int): List<BrowseNode> {
        wake()
        // One that cannot be read again stays as listed, so it does not empty the list.
        return api.started(limit).map { (listed, fresh) -> (fresh ?: listed).node() }
    }

    override suspend fun holds(tab: String): Boolean {
        val type = MediaType.entries.firstOrNull { it.api == tab } ?: return false
        return indexed(type)
    }

    override suspend fun library(tab: String, offset: Int, limit: Int): List<BrowseNode> {
        val type = MediaType.entries.firstOrNull { it.api == tab } ?: return emptyList()
        wake()
        return api.libraryItems(type, LibrarySort.AToZ, offset = offset, limit = limit).map { it.node() }
    }

    override suspend fun children(parentUri: String, offset: Int, limit: Int): List<BrowseNode> {
        val parent = ItemUri.parse(parentUri) ?: return emptyList()
        wake()
        val page = when (parent.mediaType) {
            MediaType.Podcast.api -> api.podcastEpisodes(parent.itemId, parent.provider, offset, limit)
            MediaType.Playlist.api -> api.playlistTracks(parent.itemId, parent.provider, offset, limit)
            MediaType.Album.api -> api.albumTracks(parent.itemId, parent.provider, offset, limit)
            MediaType.Artist.api -> api.artistAlbums(parent.itemId, parent.provider, offset, limit)
            else -> return emptyList()
        }
        // A server that cannot page a listing answers everything from the offset on.
        return page.items.take(limit).map { it.node() }
    }

    override suspend fun search(query: String, focus: String?): String? {
        wake()
        return bestMatch(query, focus, api.search(query, limitPerType = SEARCH_LIMIT))?.uri
    }

    private fun MediaItem.node() = BrowseNode(
        uri = uri,
        title = name,
        subtitle = subtitle,
        cover = cover,
        browsable = mediaType in BROWSABLE,
        mediaType = mediaType,
        played = if (isSpokenWord) {
            listening(
                duration,
                resumePositionMs,
                fullyPlayed == true,
            )?.first?.toDouble() ?: 0.0
        } else {
            null
        },
        finished = fullyPlayed == true,
    )
}

/**
 * What a spoken request names: of the kind the assistant heard, if it says, the item named exactly
 * so (an artist before an album, a playlist or a song), else the closest song.
 */
internal fun bestMatch(query: String, focus: String?, results: SearchResults): MediaItem? {
    val focused = when (focus) {
        MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> results.artists
        MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> results.albums
        PLAYLIST_FOCUS -> results.playlists
        MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> results.tracks
        else -> null
    }
    val kinds = focused?.let(::listOf) ?: with(results) {
        listOf(artists, albums, playlists, tracks, audiobooks, podcasts, radio)
    }
    val named = query.trim()
    return kinds.firstNotNullOfOrNull { items -> items.firstOrNull { it.name.equals(named, ignoreCase = true) } }
        ?: (focused ?: results.tracks).firstOrNull()
        ?: kinds.firstNotNullOfOrNull { it.firstOrNull() }
}

/** What opens as a folder in the car, as [DataCatalog.children] lists it; the rest plays. */
private val BROWSABLE = setOf(MediaType.Album, MediaType.Playlist, MediaType.Artist, MediaType.Podcast).map { it.api }

/** Enough per kind to find an exact name among near ones. */
private const val SEARCH_LIMIT = 5

/**
 * MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE, deprecated with the playlists table; the assistant
 * still sends it as the focus.
 */
private const val PLAYLIST_FOCUS = "vnd.android.cursor.item/playlist"
