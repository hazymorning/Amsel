package io.github.hazymorning.amsel.data.library

import io.github.hazymorning.amsel.api.FavoriteUpdate
import io.github.hazymorning.amsel.api.ItemUri
import io.github.hazymorning.amsel.api.LibrarySort
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.MaEvent
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.PlayProgress
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.api.allLibraryItems
import io.github.hazymorning.amsel.api.inProgress
import io.github.hazymorning.amsel.api.libraryCount
import io.github.hazymorning.amsel.api.libraryItems
import io.github.hazymorning.amsel.api.orNullOnFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The kinds the library index holds: the Library tab's, and radio stations for Home. */
val INDEXED_TYPES =
    listOf(
        MediaType.Playlist,
        MediaType.Album,
        MediaType.Artist,
        MediaType.Audiobook,
        MediaType.Podcast,
        MediaType.Radio,
    )

/**
 * The library index: a paged full sync on first connect, kept current from media-item events,
 * and re-synced when the server, its schema, or a provider sync says so.
 * Kinds sync side by side, so a large album library does not hold up the artists.
 */
class LibraryRepository(
    private val dao: LibraryDao,
    private val api: MaApi,
    /** The signed-in person's id: progress events arrive for every user. */
    private val userId: () -> String? = { null },
) {
    private val locks = INDEXED_TYPES.associateWith { Mutex() }
    private val running = MutableStateFlow(emptySet<MediaType>())

    /**
     * The events heard while each kind's listing is on its way: the listing can be older than they
     * are, so they apply again once it has replaced the kind.
     */
    private val heard = HashMap<MediaType, MutableList<MaEvent>>()
    private val _failures = MutableStateFlow<Map<MediaType, Exception>>(emptyMap())

    /** Why each kind's last sync failed; a kind leaves once it syncs. */
    val failures: StateFlow<Map<MediaType, Exception>> = _failures.asStateFlow()

    private val _replaced = MutableSharedFlow<MediaType>(extraBufferCapacity = INDEXED_TYPES.size)

    /** Each kind a sync has just replaced in the index. */
    val replaced: SharedFlow<MediaType> = _replaced.asSharedFlow()
    private val _progress =
        MutableSharedFlow<ProgressUpdate>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Books and episodes the signed-in person moved through, finished or marked, as it happens. */
    val progress: SharedFlow<ProgressUpdate> = _progress.asSharedFlow()

    /** True while any kind syncs; the UI shows skeletons only when the list is also empty. */
    val syncing: Flow<Boolean> = running.map { it.isNotEmpty() }.distinctUntilChanged()

    /**
     * The kinds synced from this server at least once: an empty one of them is empty, where one
     * still on its first sync is loading.
     */
    val synced: Flow<Set<MediaType>> =
        dao.syncedTypes().map { names -> INDEXED_TYPES.filter { it.api in names }.toSet() }.distinctUntilChanged()

    fun observe(type: MediaType, sort: LibrarySort): Flow<List<LibraryItemEntity>> = when (sort) {
        LibrarySort.AToZ -> dao.byName(type.api)
        LibrarySort.RecentlyAdded -> dao.byAdded(type.api)
        LibrarySort.RecentlyPlayed -> dao.byPlayed(type.api)
    }

    /** The first [limit] of [type] in [sort] order, for a Home section. */
    suspend fun firstRows(type: MediaType, sort: LibrarySort, limit: Int): List<LibraryItemEntity> = when (sort) {
        LibrarySort.AToZ -> dao.firstByName(type.api, limit)
        LibrarySort.RecentlyAdded -> dao.firstByAdded(type.api, limit)
        LibrarySort.RecentlyPlayed -> dao.firstByPlayed(type.api, limit)
    }

    suspend fun favorites(type: MediaType, limit: Int): List<LibraryItemEntity> = dao.favorites(type.api, limit)

    suspend fun random(type: MediaType, limit: Int): List<LibraryItemEntity> = dao.random(type.api, limit)

    /**
     * Smart playlists A-Z: the Smart Playlists plugin's are the ones a person made, so Music
     * Assistant's built-in set fills in only for someone who has none.
     */
    suspend fun smartPlaylists(limit: Int): List<LibraryItemEntity> =
        dao.smartPlaylists(pluginOnly = true, limit).ifEmpty { dao.smartPlaylists(pluginOnly = false, limit) }

    /** The user's and the music services' playlists, last played first. */
    suspend fun ownPlaylists(limit: Int): List<LibraryItemEntity> = dao.ownPlaylists(limit)

    /** The kinds the index holds any of; books and podcasts are offered only then. */
    val heldTypes: Flow<Set<MediaType>> =
        dao.mediaTypes().map { kinds -> INDEXED_TYPES.filter { it.api in kinds }.toSet() }.distinctUntilChanged()

    /** The index holds nothing: a library without music, or one whose first sync has not finished. */
    val isEmpty: Flow<Boolean> = dao.isEmpty().distinctUntilChanged()

    suspend fun get(uri: String): LibraryItemEntity? = dao.get(uri)

    /** Every item in the index, again after each change to it, for Search's forgiving matches. */
    val everything: Flow<List<LibraryItemEntity>> = dao.observeAll()

    /** The row for [uri] as it changes: a book page's header and progress. */
    fun observeRow(uri: String): Flow<LibraryItemEntity?> = dao.observeRow(uri).distinctUntilChanged()

    /** The index's albums whose first artist is [artistUri]: an artist page's first paint. */
    suspend fun albumsBy(artistUri: String): List<LibraryItemEntity> = dao.albumsBy(artistUri)

    /** For each of [uris] that is an artist without a picture, the album covers it wears, with no server call. */
    suspend fun albumCovers(uris: Collection<String>): Map<String, List<String>> = dao.coversOf(uris)

    /** Playlists the user can add to, A-Z (built-in smart playlists are not editable). */
    suspend fun editablePlaylists(): List<LibraryItemEntity> = dao.editablePlaylists()

    /**
     * Syncs every kind whose index is missing, belongs to another server or schema, or no longer
     * adds up to the server's: items or favorites changed while no socket was open to hear it.
     */
    suspend fun syncIfNeeded(server: ServerInfo) = syncTypes(server) { type ->
        val state = dao.syncState(type.api)
        state == null || state.serverId != server.serverId || state.schema != server.schemaVersion ||
            api.libraryCount(type) != dao.count(type.api) ||
            api.libraryCount(type, favoritesOnly = true) != dao.favoriteCount(type.api)
    }

    suspend fun syncAll(server: ServerInfo) = syncTypes(server) { true }

    /** Syncs one kind again now (Library's pull to refresh), after any sync of it already running. */
    suspend fun syncType(type: MediaType, server: ServerInfo) = sync(type, server)

    /** Syncs the kinds [needed] picks side by side; a kind that fails leaves the others alone. */
    private suspend fun syncTypes(server: ServerInfo, needed: suspend (MediaType) -> Boolean) {
        val failures = supervisorScope {
            INDEXED_TYPES.map { type -> async { if (needed(type)) sync(type, server) } }
                .mapNotNull { sync ->
                    try {
                        sync.await()
                        null
                    } catch (e: MaConnectionException) {
                        e
                    } catch (e: MaCommandException) {
                        e
                    }
                }
        }
        failures.firstOrNull()?.let { throw it }
    }

    private suspend fun sync(type: MediaType, server: ServerInfo) = locks.getValue(type).withLock {
        running.update { it + type }
        synchronized(heard) { heard[type] = mutableListOf() }
        try {
            // The recently-played ranks are asked alongside the full listing, not after it. Without
            // them the kind would sort A to Z by its recently played order until counts change.
            val (added, played) = coroutineScope {
                val played = async { recentlyPlayed(type) }
                api.allLibraryItems(type, LibrarySort.RecentlyAdded) to played.await()
            }
            val playedRank = played.withIndex().associate { (i, item) -> item.uri to i }
            dao.replaceType(
                type.api,
                added.mapIndexed { i, item ->
                    item.toEntity(addedRank = i, playedRank = playedRank[item.uri])
                },
            )
            synchronized(heard) { heard.remove(type) }.orEmpty().forEach { apply(it) }
            if (type == MediaType.Album || type == MediaType.Artist) dao.coverArtists()
            dao.setSyncState(SyncStateEntity(type.api, server.serverId, server.schemaVersion))
            _failures.update { it - type }
            _replaced.tryEmit(type)
        } catch (e: MaConnectionException) {
            _failures.update { it + (type to e) }
            throw e
        } catch (e: MaCommandException) {
            _failures.update { it + (type to e) }
            throw e
        } finally {
            synchronized(heard) { heard.remove(type) }
            running.update { it - type }
        }
    }

    /**
     * The server's recently-played order, of what has been played: the rest sorts by name after it.
     * A book's last play only moves when it is finished, so the books in progress come first, last
     * played first.
     */
    private suspend fun recentlyPlayed(type: MediaType): List<MediaItem> {
        val played = api.libraryItems(type, LibrarySort.RecentlyPlayed, playedOnly = true)
        if (type != MediaType.Audiobook) return played
        val started = orNullOnFailure { api.inProgress(IN_PROGRESS_RANKED) }.orEmpty()
            .filter { it.mediaType == MediaType.Audiobook.api }
        return (started + played).distinctBy { it.uri }
    }

    /**
     * Applies one item, favourite or progress event; returns true when it changed the index. A provider
     * sync's `music_sync_completed` is answered with [syncAll], which DataGraph runs off its event
     * loop so the player and queue events behind it are not held up.
     */
    suspend fun onEvent(event: MaEvent): Boolean {
        // Replayed over its own kind's listing only: over every kind's, a play would count again for each.
        val kind = event.objectId?.let(ItemUri::parse)?.mediaType
        synchronized(heard) {
            heard.forEach { (type, events) -> if (kind == null || kind == type.api) events += event }
        }
        return apply(event)
    }

    private suspend fun apply(event: MaEvent): Boolean {
        when (event.name) {
            "media_item_added", "media_item_updated" -> {
                val item = event.decode(MediaItem.serializer()) ?: return false
                if (!item.isLibraryItem || item.mediaType !in TYPE_NAMES) return false
                val existing = dao.get(item.uri)
                // The index holds album artists, as the sync and the count do; the server also adds
                // every track's artist and every author to the library, and announces each.
                if (existing == null && item.mediaType == MediaType.Artist.api) return false
                val addedRank = existing?.addedRank ?: ((dao.minAddedRank(item.mediaType) ?: 0) - 1)
                val entity = item.toEntityOver(existing, addedRank)
                // A metadata refresh often changes nothing the row holds: rewritten, it would re-run
                // every query that observes the index.
                if (entity == existing) return false
                dao.upsert(listOf(entity))
                // An album lends its cover to its artists; an artist that lost its picture wears its albums'.
                val lostPicture = existing?.imageProxyId != null && entity.imageProxyId == null
                if (item.mediaType == MediaType.Album.api || (item.mediaType == MediaType.Artist.api && lostPicture)) {
                    dao.coverArtists()
                }
                return true
            }

            "media_item_deleted" -> {
                val uri = event.objectId ?: return false
                if (dao.delete(uri) == 0) return false
                if (ItemUri.parse(uri)?.mediaType == MediaType.Album.api) dao.coverArtists()
                return true
            }

            "favorite_updated" -> {
                val update = event.decode(FavoriteUpdate.serializer()) ?: return false
                return dao.setFavorite(update.uri, update.favorite == true) > 0
            }

            "playlog_updated", "media_item_played" -> {
                val played = event.decode(PlayProgress.serializer()) ?: return false
                if (!MediaType.isSpokenWord(played.mediaType) || (played.userId != null && played.userId != userId())) {
                    return false
                }
                val update = ProgressUpdate(played.uri, played.fullyPlayed == true, (played.secondsPlayed ?: 0) * 1000)
                _progress.tryEmit(update)
                return dao.setProgress(update.uri, update.positionMs, update.fullyPlayed) > 0
            }
        }
        return false
    }

    /** Optimistic favourite flip; the server's event confirms or corrects it. */
    suspend fun setFavoriteLocally(uri: String, favorite: Boolean) {
        dao.setFavorite(uri, favorite)
    }

    /** A book the server just sent: its row takes the person's progress from it. */
    suspend fun rememberProgress(item: MediaItem) {
        if (item.mediaType != MediaType.Audiobook.api || !item.isLibraryItem) return
        dao.setProgress(item.uri, item.resumePositionMs, item.fullyPlayed)
    }

    suspend fun holdsSpokenWord(): Boolean = heldTypes.first().any {
        it == MediaType.Audiobook ||
            it == MediaType.Podcast
    }

    /**
     * Optimistic "Mark as finished" or "unfinished"; the server's `playlog_updated` confirms it.
     * Returns what puts back [before], where the person stood, for when the server refuses the
     * mark: an episode or a book from Search has no row, only the pages showing it.
     */
    suspend fun setPlayedLocally(uri: String, played: Boolean, before: ProgressUpdate): suspend () -> Unit {
        val row = dao.get(uri)
        _progress.tryEmit(ProgressUpdate(uri, played, positionMs = 0))
        dao.setProgress(uri, if (played) null else 0, played)
        return {
            _progress.tryEmit(before)
            if (row != null) dao.setProgress(uri, row.resumeMs, row.fullyPlayed)
        }
    }

    suspend fun clear() {
        INDEXED_TYPES.forEach { dao.clearType(it.api) }
        dao.clearSyncState()
        _failures.value = emptyMap()
    }

    private companion object {
        val TYPE_NAMES = INDEXED_TYPES.map { it.api }.toSet()
        const val IN_PROGRESS_RANKED = 100
    }
}

/** Where the signed-in person stands in a book or episode. */
data class ProgressUpdate(val uri: String, val fullyPlayed: Boolean, val positionMs: Long)
