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

sealed interface PlaylistEdit {
    val playlistKey: String

    /** [position] is 1-based, as the server numbers a playlist's tracks. */
    data class Removed(override val playlistKey: String, val position: Int) : PlaylistEdit

    data class Failed(override val playlistKey: String) : PlaylistEdit

    /** No server event announces the new contents, so an open page reads them again. */
    data class Added(override val playlistKey: String) : PlaylistEdit
}

class AddedToPlaylist(val playlist: MediaUi, val open: (() -> Unit)?)

class PlaylistEditor(
    private val api: MaApi,
    private val library: LibraryRepository,
    private val mapper: UiMapper,
    private val messages: MessageController,
    private val scope: CoroutineScope,
) {
    private val _added = Channel<AddedToPlaylist>(ADDED_BUFFER, BufferOverflow.DROP_OLDEST)

    val added: Flow<AddedToPlaylist> = _added.receiveAsFlow()

    private val _edits =
        MutableSharedFlow<PlaylistEdit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * For the open page, as no server event announces these. After a removal the later positions
     * move up by one, as on the server.
     */
    val edits: SharedFlow<PlaylistEdit> = _edits.asSharedFlow()

    // One removal at a time: each position already counts the removals before it.
    private val removals = Mutex()

    // A removal queued behind a failed one counted on a list that never existed, so it is dropped.
    private val failedRemovals = ConcurrentHashMap<String, Int>()

    suspend fun editable(): List<MediaUi> = library.editablePlaylists().map(mapper::media)

    fun add(uri: String, playlist: MediaUi, open: (() -> Unit)? = null) {
        val id = ItemUri.parse(playlist.key)?.itemId ?: return
        scope.launch {
            val error = try {
                api.addToPlaylist(id, listOf(uri))
                _edits.emit(PlaylistEdit.Added(playlist.key))
                _added.trySend(AddedToPlaylist(playlist, open))
                return@launch
            } catch (e: MaException) {
                e
            }
            messages.show(failureText(error), retry = { add(uri, playlist, open) })
        }
    }

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
                    // After a lost connection the removal may have run, and sent again its position would
                    // hit the next song: the page reads the playlist again instead.
                    val retry = if (error is MaCommandException) ({ remove(target) }) else null
                    messages.show(failureText(error), retry = retry)
                }
            }
        }
    }
}

// A burst of adds drops the oldest; the hints say the latest.
private const val ADDED_BUFFER = 8
