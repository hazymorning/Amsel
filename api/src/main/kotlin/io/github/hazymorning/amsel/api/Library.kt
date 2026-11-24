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

/** Artists are album artists only, as Music Assistant's own Artists view lists them. */
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

public suspend fun MaApi.mostPlayed(type: MediaType, limit: Int): List<MediaItem> {
    val typedSort = typedSort()
    return list("music/${type.plural}/library_items", MediaItem.serializer()) {
        put("limit", limit)
        put("played_only", true)
        albumArtistsOnly(type)
        putSort(field = "play_count", direction = "desc", typedSort)
    }
}

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

public suspend fun MaApi.albumTracks(itemId: String, provider: String, offset: Int, limit: Int): ItemPage<MediaItem> =
    listingPage("music/albums/album_tracks", Schema.ALBUM_TRACK_PAGING, offset, limit) { ids(itemId, provider) }

/** For a library artist, the server lists library albums only. */
public suspend fun MaApi.artistAlbums(itemId: String, provider: String): List<MediaItem> =
    listEvery("music/artists/artist_albums", Schema.LISTING_PAGING) { ids(itemId, provider) }

public suspend fun MaApi.artistAlbums(itemId: String, provider: String, offset: Int, limit: Int): ItemPage<MediaItem> =
    listingPage("music/artists/artist_albums", Schema.LISTING_PAGING, offset, limit) { ids(itemId, provider) }

/**
 * From schema [paged] a call without `limit` answers at most 500 items; before it the server
 * ignores `offset` and answers all at once.
 */
private suspend fun MaApi.listEvery(command: String, paged: Int, build: JsonObjectBuilder.() -> Unit): List<MediaItem> =
    if (schema() >= paged) {
        listAll(command, MediaItem.serializer(), build = build)
    } else {
        list(command, MediaItem.serializer(), build = build)
    }

/** Before schema [paged] the server cannot page: everything from [offset] on comes as the last page. */
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

/** Before schema 93 the whole playlist streams in partials, and everything from [offset] comes as one page. */
public suspend fun MaApi.playlistTracks(
    itemId: String,
    provider: String,
    offset: Int = 0,
    limit: Int = MaApi.PAGE,
): ItemPage<MediaItem> =
    listingPage("music/playlists/playlist_tracks", Schema.PLAYLIST_PAGING, offset, limit, STREAM_TIMEOUT) {
        ids(itemId, provider)
    }

/** The server interleaves library and provider results, so each list is re-sorted, library first. */
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
 * The calling user's history, newest first. With [queueId], only items whose last play was on that
 * speaker: the server keeps one play-log row per item and user.
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
 * Returns once `tasks_updated` lists the task as finished. Listening starts before the command is
 * sent, as that event can arrive before the answer that names the task.
 */
private suspend fun MaApi.runTask(command: String, args: JsonObject): Unit = coroutineScope {
    val updates = Channel<JsonElement>(Channel.UNLIMITED)
    val listening = launch(start = CoroutineStart.UNDISPATCHED) {
        client.events.collect { if (it.name == TASKS_UPDATED) it.data?.let(updates::trySend) }
    }
    try {
        val (answer, closed) = client.callOnConnection(command, args)
        val id = answer.decodeOrNull(BackgroundTask.serializer())?.id ?: return@coroutineScope
        // A server restart drops the task without an event, so the wait ends with this connection.
        val task = merge<BackgroundTask?>(
            updates.receiveAsFlow()
                .mapNotNull { tasks ->
                    tasks.decodeOrNull(TaskListSerializer)?.firstOrNull { it.id == id && it.finished }
                },
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

private fun JsonObjectBuilder.albumArtistsOnly(type: MediaType) {
    if (type == MediaType.Artist) put("album_artists_only", true)
}

private suspend fun MaApi.typedSort(): Boolean = schema() >= Schema.TYPED_SORT

private fun JsonObjectBuilder.putSort(sort: LibrarySort, typed: Boolean) = when (sort) {
    LibrarySort.AToZ -> putSort("sort_name", "asc", typed)
    LibrarySort.RecentlyAdded -> putSort("timestamp_added", "desc", typed)
    LibrarySort.RecentlyPlayed -> putSort("last_played", "desc", typed)
}

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
