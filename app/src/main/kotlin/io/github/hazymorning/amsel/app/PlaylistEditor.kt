package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.api.ItemUri
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.addToPlaylist
import io.github.hazymorning.amsel.api.removeFromPlaylist
import io.github.hazymorning.amsel.data.library.LibraryRepository
import io.github.hazymorning.amsel.playback.failureText
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.MediaUi
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the ⋮ sheet did to playlist [playlistKey], for its open page. */
sealed interface PlaylistEdit {
    val playlistKey: String

    /** The track at 1-based [position] goes at once; the server's task follows. */
    data class Removed(override val playlistKey: String, val position: Int) : PlaylistEdit

    /** A removal did not happen: the page reads the playlist again. */
    data class Failed(override val playlistKey: String) : PlaylistEdit

    /** Songs went into it: an open page reads it again, as no event says so. */
    data class Added(override val playlistKey: String) : PlaylistEdit
}

/** A song went into [playlist]; [open] leads to it, where the sheet that added it can go there. */
class AddedTo(val playlist: MediaUi, val open: (() -> Unit)?)

/** The ⋮ sheet's changes to playlists: adding to one, and removing a track from one. */
class PlaylistEditor(
    private val api: MaApi,
    private val library: LibraryRepository,
    private val mapper: UiMapper,
    private val messages: Messages,
    private val scope: CoroutineScope,
) {
    private val _added = Channel<AddedTo>(ADDED_BUFFER, BufferOverflow.DROP_OLDEST)

    /** Songs that went into a playlist from here, for the hints to say so ([Hints]). */
    val added: Flow<AddedTo> = _added.receiveAsFlow()

    private val _edits =
        MutableSharedFlow<PlaylistEdit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Changes to a playlist for its open page: a removal drops the row at once and the positions
     * after it move up, as the server's do; an addition reads it again. No event announces either.
     */
    val edits: SharedFlow<PlaylistEdit> = _edits.asSharedFlow()

    /** One removal at a time: each names a position that counts the removals before it. */
    private val removals = Mutex()

    /** Failed removals by playlist; one queued behind a failure counted on a list that never was. */
    private val failedRemovals = ConcurrentHashMap<String, Int>()

    /** Playlists a track or album can be added to: the editable ones in the library. */
    suspend fun editable(): List<MediaUi> = library.editablePlaylists().map(mapper::media)

    fun add(uri: String, playlist: MediaUi, open: (() -> Unit)? = null) {
        val id = ItemUri.parse(playlist.key)?.itemId ?: return
        scope.launch {
            val error = try {
                api.addToPlaylist(id, listOf(uri))
                _edits.emit(PlaylistEdit.Added(playlist.key))
                _added.trySend(AddedTo(playlist, open))
                return@launch
            } catch (e: MaException) {
                e
            }
            messages.show(failureText(error), retry = { add(uri, playlist, open) })
        }
    }

    /** Removes [target] from its playlist: the open page drops the row at once ([edits]); the server's task follows. */
    fun remove(target: ActionTarget.Track) {
        val playlistKey = target.playlistKey ?: return
        val playlistId = ItemUri.parse(playlistKey)?.itemId ?: return
        val position = target.position ?: return
        val failedBefore = failedRemovals[playlistKey] ?: 0
        _edits.tryEmit(PlaylistEdit.Removed(playlistKey, position))
        // Undispatched, so removals queue on the fair lock in the order they were made.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            removals.withLock {
                if ((failedRemovals[playlistKey] ?: 0) != failedBefore) return@withLock
                val error = try {
                    api.removeFromPlaylist(playlistId, listOf(position))
                    null
                } catch (e: MaException) {
                    e
                }
                if (error != null) {
                    failedRemovals.merge(playlistKey, 1, Int::plus)
                    _edits.emit(PlaylistEdit.Failed(playlistKey))
                    // Cut off, the removal may have run: sent again, that position would be the next
                    // song. The page reads the playlist again instead.
                    val retry = if (error is MaCommandException) ({ remove(target) }) else null
                    messages.show(failureText(error), retry = retry)
                }
            }
        }
    }
}

/** Adds made in a burst: the hints say the latest. */
private const val ADDED_BUFFER = 8
