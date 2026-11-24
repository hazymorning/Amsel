package io.github.hazymorning.amsel.data.library

import io.github.hazymorning.amsel.api.FavoriteUpdate
import io.github.hazymorning.amsel.api.ItemUri
import io.github.hazymorning.amsel.api.LibrarySort
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaEvent
import io.github.hazymorning.amsel.api.MaException
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

val INDEXED_TYPES =
    listOf(
        MediaType.Playlist,
        MediaType.Album,
        MediaType.Artist,
        MediaType.Audiobook,
        MediaType.Podcast,
        MediaType.Radio,
    )

// Kinds sync side by side, so a large album library does not hold up the artists.
class LibraryRepository(
    private val dao: LibraryDao,
    private val api: MaApi,
    // Progress events arrive for every user.
    private val userId: () -> String? = { null },
) {
    private val locks = INDEXED_TYPES.associateWith { Mutex() }
    private val running = MutableStateFlow(emptySet<MediaType>())

    // Events heard while a kind's listing loads; the listing can be older, so they are applied again over it.
    private val heard = HashMap<MediaType, MutableList<MaEvent>>()
    private val _failures = MutableStateFlow<Map<MediaType, Exception>>(emptyMap())

    val failures: StateFlow<Map<MediaType, Exception>> = _failures.asStateFlow()

    private val _replaced = MutableSharedFlow<MediaType>(extraBufferCapacity = INDEXED_TYPES.size)

    val replaced: SharedFlow<MediaType> = _replaced.asSharedFlow()
    private val _progress =
        MutableSharedFlow<ProgressUpdate>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Only the signed-in user's progress. */
    val progress: SharedFlow<ProgressUpdate> = _progress.asSharedFlow()

    val syncing: Flow<Boolean> = running.map { it.isNotEmpty() }.distinctUntilChanged()

    /** Kinds synced at least once: one of them that is empty is really empty, any other is still loading. */
    val synced: Flow<Set<MediaType>> =
        dao.syncedTypes().map { names -> INDEXED_TYPES.filter { it.api in names }.toSet() }.distinctUntilChanged()

    fun observe(type: MediaType, sort: LibrarySort): Flow<List<LibraryItemEntity>> = when (sort) {
        LibrarySort.AToZ -> dao.byName(type.api)
        LibrarySort.RecentlyAdded -> dao.byAdded(type.api)
        LibrarySort.RecentlyPlayed -> dao.byPlayed(type.api)
    }

    suspend fun firstRows(type: MediaType, sort: LibrarySort, limit: Int): List<LibraryItemEntity> = when (sort) {
        LibrarySort.AToZ -> dao.firstByName(type.api, limit)
        LibrarySort.RecentlyAdded -> dao.firstByAdded(type.api, limit)
        LibrarySort.RecentlyPlayed -> dao.firstByPlayed(type.api, limit)
    }

    suspend fun favorites(type: MediaType, limit: Int): List<LibraryItemEntity> = dao.favorites(type.api, limit)

    suspend fun random(type: MediaType, limit: Int): List<LibraryItemEntity> = dao.random(type.api, limit)

    // The Smart Playlists plugin's are the ones the user made; Music Assistant's built-in set shows only without them.
    suspend fun smartPlaylists(limit: Int): List<LibraryItemEntity> =
        dao.smartPlaylists(pluginOnly = true, limit).ifEmpty { dao.smartPlaylists(pluginOnly = false, limit) }

    /** Every playlist that is not smart: the user's and the music services'. */
    suspend fun ownPlaylists(limit: Int): List<LibraryItemEntity> = dao.ownPlaylists(limit)

    val heldTypes: Flow<Set<MediaType>> =
        dao.mediaTypes().map { kinds -> INDEXED_TYPES.filter { it.api in kinds }.toSet() }.distinctUntilChanged()

    val isEmpty: Flow<Boolean> = dao.isEmpty().distinctUntilChanged()

    suspend fun get(uri: String): LibraryItemEntity? = dao.get(uri)

    suspend fun rows(uris: Collection<String>): Map<String, LibraryItemEntity> = dao.rowsOf(uris)

    val everything: Flow<List<LibraryItemEntity>> = dao.observeAll()

    fun observeRow(uri: String): Flow<LibraryItemEntity?> = dao.observeRow(uri).distinctUntilChanged()

    suspend fun albumsBy(artistUri: String): List<LibraryItemEntity> = dao.albumsBy(artistUri)

    suspend fun albumCovers(uris: Collection<String>): Map<String, List<String>> = dao.coversOf(uris)

    suspend fun editablePlaylists(): List<LibraryItemEntity> = dao.editablePlaylists()

    // Counts that differ mean items or favourites changed while no socket was open to hear it.
    suspend fun syncIfNeeded(server: ServerInfo) = syncTypes(server) { type ->
        val state = dao.syncState(type.api)
        state == null || state.serverId != server.serverId || state.schema != server.schemaVersion ||
            api.libraryCount(type) != dao.count(type.api) ||
            api.libraryCount(type, favoritesOnly = true) != dao.favoriteCount(type.api)
    }

    suspend fun syncAll(server: ServerInfo) = syncTypes(server) { true }

    suspend fun syncType(type: MediaType, server: ServerInfo) = sync(type, server)

    private suspend fun syncTypes(server: ServerInfo, needed: suspend (MediaType) -> Boolean) {
        val failures = supervisorScope {
            INDEXED_TYPES.map { type -> async { if (needed(type)) sync(type, server) } }
                .mapNotNull { sync ->
                    try {
                        sync.await()
                        null
                    } catch (e: MaException) {
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
            // Asked with the listing, not after it: without the ranks, Recently played would sort A to Z until
            // the counts change.
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
        } catch (e: MaException) {
            _failures.update { it + (type to e) }
            throw e
        } finally {
            synchronized(heard) { heard.remove(type) }
            running.update { it - type }
        }
    }

    // A book's last play moves only once it is finished, so the books in progress come first, last played first.
    // See docs/API-NOTES.md, "Audiobooks and podcasts".
    private suspend fun recentlyPlayed(type: MediaType): List<MediaItem> {
        val played = api.libraryItems(type, LibrarySort.RecentlyPlayed, playedOnly = true)
        if (type != MediaType.Audiobook) return played
        val started = orNullOnFailure { api.inProgress(IN_PROGRESS_RANKED) }.orEmpty()
            .filter { it.mediaType == MediaType.Audiobook.api }
        return (started + played).distinctBy { it.uri }
    }

    /** True when the event changed the index. `music_sync_completed` is DataGraph's: [syncAll], off the event loop. */
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
                // The index holds album artists only, as the sync and the count do; the server also adds every
                // track's artist and every author to the library, and announces each.
                if (existing == null && item.mediaType == MediaType.Artist.api) return false
                val addedRank = existing?.addedRank ?: ((dao.minAddedRank(item.mediaType) ?: 0) - 1)
                val entity = item.toEntityOver(existing, addedRank)
                // A metadata refresh often changes nothing in the row; a rewrite would re-run every query that
                // observes the index.
                if (entity == existing) return false
                dao.upsert(listOf(entity))
                // An album's cover goes to its artists without a picture, and to an artist that lost its picture.
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

    /** For a book from a direct request, whose progress is the calling user's, unlike an item event's. */
    suspend fun rememberProgress(item: MediaItem) {
        if (item.mediaType != MediaType.Audiobook.api || !item.isLibraryItem) return
        dao.setProgress(item.uri, item.resumePositionMs, item.fullyPlayed)
    }

    suspend fun holdsSpokenWord(): Boolean = heldTypes.first().any {
        it == MediaType.Audiobook ||
            it == MediaType.Podcast
    }

    /**
     * Optimistic mark; the server's `playlog_updated` confirms it. Returns the undo for a refused mark, which restores
     * [before] also where the index has no row (an episode, or a book from Search).
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

data class ProgressUpdate(val uri: String, val fullyPlayed: Boolean, val positionMs: Long)
