package io.github.hazymorning.amsel.api

import kotlin.time.Duration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * One page of library items in the given order; artists are album artists only. Radio
 * stations (`MediaType.Radio`) list and sort the same way, and play through [playMedia] by
 * their uri.
 */
public suspend fun MaApi.libraryItems(
    type: MediaType,
    sort: LibrarySort = LibrarySort.AToZ,
    offset: Int = 0,
    limit: Int = MaApi.PAGE,
    playedOnly: Boolean = false,
): List<MediaItem> {
    val typedSort = typedSort()
    return list("music/${type.plural}/library_items", MediaItem.serializer()) {
        put("offset", offset)
        put("limit", limit)
        if (playedOnly) put("played_only", true)
        libraryArgs(type, sort, typedSort)
    }
}

/** The library's [type] items that have been played, the most played first (`play_count`). */
public suspend fun MaApi.mostPlayed(type: MediaType, limit: Int): List<MediaItem> {
    val typedSort = typedSort()
    return list("music/${type.plural}/library_items", MediaItem.serializer()) {
        put("limit", limit)
        put("played_only", true)
        albumArtistsOnly(type)
        putSort(field = "play_count", direction = "desc", typedSort)
    }
}

/** How many library items of [type] there are, as [libraryItems] lists them; or only the favorites. */
public suspend fun MaApi.libraryCount(type: MediaType, favoritesOnly: Boolean = false): Int {
    val count = client.call(
        "music/${type.plural}/count",
        buildJsonObject {
            if (favoritesOnly) put("favorite_only", true)
            albumArtistsOnly(type)
        },
    )
    return (count as? JsonPrimitive)?.intOrNull ?: throw MaConnectionException("No count for ${type.plural}")
}

public suspend fun MaApi.allLibraryItems(type: MediaType, sort: LibrarySort = LibrarySort.AToZ): List<MediaItem> {
    val typedSort = typedSort()
    return listAll("music/${type.plural}/library_items", MediaItem.serializer()) {
        libraryArgs(type, sort, typedSort)
    }
}

public suspend fun MaApi.itemByUri(uri: String): MediaItem? =
    client.call("music/item_by_uri", buildJsonObject { put("uri", uri) }).decodeOrNull(MediaItem.serializer())

public suspend fun MaApi.albumTracks(itemId: String, provider: String): List<MediaItem> =
    listEvery("music/albums/album_tracks", Schema.ALBUM_TRACK_PAGING) { ids(itemId, provider) }

/** Up to [limit] of an album's tracks from [offset]. */
public suspend fun MaApi.albumTracks(itemId: String, provider: String, offset: Int, limit: Int): ItemPage<MediaItem> =
    listingPage("music/albums/album_tracks", Schema.ALBUM_TRACK_PAGING, offset, limit) { ids(itemId, provider) }

/** Every album of the artist; for a library artist the server reads only its own library. */
public suspend fun MaApi.artistAlbums(itemId: String, provider: String): List<MediaItem> =
    listEvery("music/artists/artist_albums", Schema.LISTING_PAGING) { ids(itemId, provider) }

/** Up to [limit] of the artist's albums from [offset]. */
public suspend fun MaApi.artistAlbums(itemId: String, provider: String, offset: Int, limit: Int): ItemPage<MediaItem> =
    listingPage("music/artists/artist_albums", Schema.LISTING_PAGING, offset, limit) { ids(itemId, provider) }

/** All of a listing that pages from schema [paged] and answers everything before. */
private suspend fun MaApi.listEvery(command: String, paged: Int, build: JsonObjectBuilder.() -> Unit): List<MediaItem> =
    if (schema() >= paged) {
        listAll(command, MediaItem.serializer(), build = build)
    } else {
        list(command, MediaItem.serializer(), build = build)
    }

/** A page of a listing that pages from schema [paged]; before it, everything from [offset] on. */
private suspend fun MaApi.listingPage(
    command: String,
    paged: Int,
    offset: Int,
    limit: Int,
    timeout: Duration = MaSocket.CALL_TIMEOUT,
    build: JsonObjectBuilder.() -> Unit,
): ItemPage<MediaItem> = if (schema() >= paged) {
    page(command, MediaItem.serializer(), offset, limit, timeout, build)
} else {
    list(command, MediaItem.serializer(), timeout, build).pageFrom(offset)
}

/**
 * Up to [limit] of a playlist's tracks from [offset], each with its position. Before schema 93
 * the server cannot page: the whole playlist streams in partials, so everything from [offset]
 * comes at once.
 */
public suspend fun MaApi.playlistTracks(
    itemId: String,
    provider: String,
    offset: Int = 0,
    limit: Int = MaApi.PAGE,
): ItemPage<MediaItem> =
    listingPage("music/playlists/playlist_tracks", Schema.PLAYLIST_PAGING, offset, limit, STREAM_TIMEOUT) {
        ids(itemId, provider)
    }

/** One call; each list is re-sorted so the library's matches come first. */
public suspend fun MaApi.search(query: String, limitPerType: Int = 10): SearchResults {
    if (query.isBlank()) return SearchResults()
    val raw = client.call(
        "music/search",
        buildJsonObject {
            put("search_query", query)
            put("limit", limitPerType)
            putJsonArray("media_types") { SEARCHED.forEach { add(JsonPrimitive(it.api)) } }
        },
    ).decodeOrNull(SearchResults.serializer()) ?: return SearchResults()
    fun List<MediaItem>.libraryFirst() = sortedBy { if (it.isLibraryItem) 0 else 1 }
    return SearchResults(
        raw.artists.libraryFirst(),
        raw.albums.libraryFirst(),
        raw.tracks.libraryFirst(),
        raw.playlists.libraryFirst(),
        raw.radio.libraryFirst(),
        raw.audiobooks.libraryFirst(),
        raw.podcasts.libraryFirst(),
    )
}

/**
 * What the person played lately, newest first: albums, playlists, artists and stations; with
 * [queueId], only what last played on that speaker.
 */
public suspend fun MaApi.recentlyPlayed(
    limit: Int,
    types: List<MediaType> = listOf(MediaType.Album, MediaType.Playlist, MediaType.Artist, MediaType.Radio),
    queueId: String? = null,
): List<MediaItem> = list("music/recently_played_items", MediaItem.serializer()) {
    put("limit", limit)
    putJsonArray("media_types") { types.forEach { add(JsonPrimitive(it.api)) } }
    put("user_initiated_only", true)
    queueId?.let { put("queue_id", it) }
}

// Favourites and playlist edits need the library.write scope.

public suspend fun MaApi.setFavorite(item: ItemUri, favorite: Boolean) {
    when {
        schema() >= Schema.FAVORITES_PER_USER -> client.call(
            "music/favorites/set_item",
            buildJsonObject {
                put("item", item.toString())
                if (favorite) put("favorite", true) else put("favorite", JsonNull)
            },
        )

        favorite -> client.call("music/favorites/add_item", buildJsonObject { put("item", item.toString()) })

        else -> {
            val libraryId = if (item.isLibraryItem) {
                item.itemId
            } else {
                client.call(
                    "music/get_library_item",
                    buildJsonObject {
                        put("media_type", item.mediaType)
                        ids(item.itemId, item.provider)
                    },
                ).decodeOrNull(MediaItem.serializer())?.itemId ?: return
            }
            client.call(
                "music/favorites/remove_item",
                buildJsonObject {
                    put("media_type", item.mediaType)
                    put("library_item_id", libraryId)
                },
            )
        }
    }
}

public suspend fun MaApi.addToPlaylist(dbPlaylistId: String, uris: List<String>): Unit = runTask(
    "music/playlists/add_playlist_tracks",
    buildJsonObject {
        put("db_playlist_id", dbPlaylistId)
        putJsonArray("uris") { uris.forEach { add(JsonPrimitive(it)) } }
    },
)

/** [positions] are the 1-based `position` values from [playlistTracks]. */
public suspend fun MaApi.removeFromPlaylist(dbPlaylistId: String, positions: List<Int>): Unit = runTask(
    "music/playlists/remove_playlist_tracks",
    buildJsonObject {
        put("db_playlist_id", dbPlaylistId)
        putJsonArray("positions_to_remove") { positions.forEach { add(JsonPrimitive(it)) } }
    },
)

/**
 * Sends [command], which the server answers with a background task, and returns once
 * `tasks_updated` lists that task as finished; throws [MaCommandException] when it failed, and
 * [MaConnectionException] when the connection closed first, so whether it ran is not known. The
 * task's id comes with the answer, so listening starts before the command is sent.
 */
private suspend fun MaApi.runTask(command: String, args: JsonObject): Unit = coroutineScope {
    val updates = Channel<JsonElement>(Channel.UNLIMITED)
    val listening = launch(start = CoroutineStart.UNDISPATCHED) {
        client.events.collect { if (it.name == TASKS_UPDATED) it.data?.let(updates::trySend) }
    }
    try {
        val (answer, closed) = client.callOnConnection(command, args)
        val id = answer.decodeOrNull(BackgroundTask.serializer())?.id ?: return@coroutineScope
        // Every connection of this user hears how the task ends, but a server restart drops it without
        // a word: waiting past this connection could wait for ever.
        val task = merge<BackgroundTask?>(
            updates.receiveAsFlow()
                .mapNotNull { tasks -> tasks.decodeOrNull(TaskList)?.firstOrNull { it.id == id && it.finished } },
            flow {
                closed.await()
                emit(null)
            },
        ).first() ?: throw MaConnectionException("The connection closed before $command finished")
        if (task.failed) {
            throw MaCommandException(
                command,
                MaCommandException.ERROR_UNKNOWN,
                task.lastError ?: task.status,
            )
        }
    } finally {
        listening.cancel()
    }
}

private fun JsonObjectBuilder.libraryArgs(type: MediaType, sort: LibrarySort, typedSort: Boolean) {
    albumArtistsOnly(type)
    putSort(sort, typedSort)
}

/** The library's artists are those with albums, as Music Assistant's own Artists view lists them. */
private fun JsonObjectBuilder.albumArtistsOnly(type: MediaType) {
    if (type == MediaType.Artist) put("album_artists_only", true)
}

private suspend fun MaApi.typedSort(): Boolean = schema() >= Schema.TYPED_SORT

private fun JsonObjectBuilder.putSort(sort: LibrarySort, typed: Boolean) = when (sort) {
    LibrarySort.AToZ -> putSort("sort_name", "asc", typed)
    LibrarySort.RecentlyAdded -> putSort("timestamp_added", "desc", typed)
    LibrarySort.RecentlyPlayed -> putSort("last_played", "desc", typed)
}

/** Typed sort from schema 86; before it, `order_by`, with `_desc` for a descending order. */
private fun JsonObjectBuilder.putSort(field: String, direction: String, typed: Boolean) {
    if (typed) {
        put("sort_field", field)
        put("sort_direction", direction)
    } else {
        put("order_by", if (direction == "desc") "${field}_desc" else field)
    }
}

private const val TASKS_UPDATED = "tasks_updated"

private val SEARCHED = listOf(
    MediaType.Artist,
    MediaType.Album,
    MediaType.Track,
    MediaType.Playlist,
    MediaType.Radio,
    MediaType.Audiobook,
    MediaType.Podcast,
)
