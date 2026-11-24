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

/** [played] is the played fraction (0 to 1) of a book or an episode, null for music. */
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

interface Catalog {
    suspend fun recent(limit: Int): List<BrowseNode>

    suspend fun inProgress(limit: Int): List<BrowseNode>

    /** [tab] is a media type such as "audiobook" or "podcast". */
    suspend fun holds(tab: String): Boolean

    suspend fun library(tab: String, offset: Int, limit: Int): List<BrowseNode>

    suspend fun children(parentUri: String, offset: Int, limit: Int): List<BrowseNode>

    /** [focus] is MediaStore's `EXTRA_MEDIA_FOCUS`; null when the server finds nothing. */
    suspend fun search(query: String, focus: String?): String?
}

/**
 * Each read [wake]s the session, as the car browses with the phone locked and nothing playing.
 * [covers] gives an artist without a picture one of its album covers from the index, where the app
 * shows four: the car loads a cover as one proxied image.
 */
class DataCatalog(
    private val api: MaApi,
    private val wake: () -> Unit,
    private val indexed: suspend (MediaType) -> Boolean,
    private val covers: suspend (uris: List<String>) -> Map<String, String> = { emptyMap() },
) : Catalog {
    override suspend fun recent(limit: Int): List<BrowseNode> {
        wake()
        return api.recentlyPlayed(limit, listOf(MediaType.Album, MediaType.Playlist, MediaType.Artist)).map {
            it.node()
        }.withCovers()
    }

    override suspend fun inProgress(limit: Int): List<BrowseNode> {
        wake()
        // One that cannot be read again stays as listed rather than emptying the list.
        return api.started(limit).map { (listed, fresh) -> (fresh ?: listed).node() }
    }

    override suspend fun holds(tab: String): Boolean {
        val type = MediaType.entries.firstOrNull { it.api == tab } ?: return false
        return indexed(type)
    }

    override suspend fun library(tab: String, offset: Int, limit: Int): List<BrowseNode> {
        val type = MediaType.entries.firstOrNull { it.api == tab } ?: return emptyList()
        wake()
        return api.libraryItems(type, LibrarySort.AToZ, offset = offset, limit = limit).map { it.node() }.withCovers()
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
        // A server that cannot page a listing sends everything from the offset on.
        return page.items.take(limit).map { it.node() }
    }

    override suspend fun search(query: String, focus: String?): String? {
        wake()
        return bestMatch(query, focus, api.search(query, limitPerType = SEARCH_LIMIT))?.uri
    }

    private suspend fun List<BrowseNode>.withCovers(): List<BrowseNode> {
        val bare = filter { it.cover == null && it.mediaType == MediaType.Artist.api }.map { it.uri }
        if (bare.isEmpty()) return this
        val held = covers(bare)
        return map { node ->
            val cover = held[node.uri]?.takeIf { node.cover == null } ?: return@map node
            node.copy(cover = MediaItemImage(proxyId = cover))
        }
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
 * An exact name match first (of the kind the assistant names, else an artist before an album, a
 * playlist or a song), then the first result of the named kind, or with none named the first song.
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

/** Must match the kinds [DataCatalog.children] lists. */
private val BROWSABLE = setOf(MediaType.Album, MediaType.Playlist, MediaType.Artist, MediaType.Podcast).map { it.api }

/** Enough per kind to find an exact name among near ones. */
private const val SEARCH_LIMIT = 5

/** MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE, deprecated with the playlists table; the assistant still sends it. */
private const val PLAYLIST_FOCUS = "vnd.android.cursor.item/playlist"
