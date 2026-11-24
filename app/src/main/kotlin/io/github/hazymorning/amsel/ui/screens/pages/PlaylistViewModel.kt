package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.app.MessageController
import io.github.hazymorning.amsel.app.PlaylistEdit
import io.github.hazymorning.amsel.app.retryWhileOpen
import io.github.hazymorning.amsel.data.CachedPage
import io.github.hazymorning.amsel.data.library.LibraryItemEntity
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [playing] is the URI playing on the current speaker, or starting there; [edits] are the ⋮ sheet's
 * changes to this playlist.
 */
class PlaylistViewModel(
    private val key: String,
    private val source: PageSource,
    private val mapper: UiMapper,
    playing: Flow<String?>,
    edits: Flow<PlaylistEdit>,
    private val messages: MessageController,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val page = MutableStateFlow(PlaylistUiState(loading = true))
    private var loading: Job? = null

    val state: StateFlow<PlaylistUiState> = combine(page, source.row(key), playing) { page, row, uri ->
        shown(page, row, uri)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistUiState(loading = true))

    // The index row gives the header until the server answers, and the favourite as changed anywhere.
    private fun shown(page: PlaylistUiState, row: LibraryItemEntity?, uri: String?): PlaylistUiState {
        val playlist = page.playlist ?: row?.let(mapper::playlistHeader)
        return page.copy(
            playlist = row?.let {
                playlist?.copy(favorite = it.favorite)
            } ?: playlist,
            currentTrackKey = uri,
        )
    }

    private val retryLoad = retryWhileOpen(messages, ::retry)

    init {
        viewModelScope.launch {
            edits.collect { edit ->
                when (edit) {
                    is PlaylistEdit.Removed -> removed(edit.position)

                    // Added tracks show once the playlist is read again; the rows stay meanwhile.
                    is PlaylistEdit.Added -> load()

                    // The trimmed rows no longer match the server: cleared and reloaded, so a failed
                    // load says so instead of keeping them.
                    is PlaylistEdit.Failed -> {
                        page.update { it.copy(tracks = emptyList(), positions = emptyList()) }
                        load()
                    }
                }
            }
        }
        load(first = true)
    }

    fun retry() = load()

    fun removed(position: Int) = page.update { it.without(position) }

    /** Only the [first] load shows the cached page while the server is asked. */
    private fun load(first: Boolean = false) {
        loading?.cancel()
        page.update { it.copy(loading = true, unavailable = false) }
        loading = viewModelScope.launch {
            val load = PageLoad()
            val item = async { load.attempt { source.item(key) } }
            val firstPage = async { rowsOrNull(load, offset = 0, limit = FIRST_ROWS) }
            if (first) source.shown(key)?.let { last -> paint(last) }
            val playlist = item.await()
            if (playlist != null) page.update { it.copy(playlist = mapper.playlistHeader(playlist)) }
            var rows = firstPage.await()
            val firstRows = rows
            if (playlist != null &&
                firstRows != null
            ) {
                launch { source.keep(key, CachedPage(playlist, firstRows.items)) }
            }
            page.update {
                it.copy(
                    tracks = rows?.tracks ?: it.tracks,
                    positions = rows?.positions ?: it.positions,
                    complete = rows?.complete == true,
                    loading = false,
                    unavailable = playlist == null || rows == null,
                )
            }
            while (rows != null && !rows.complete) {
                rows = rowsOrNull(load, rows.next)
                page.update { shown ->
                    rows?.let { more ->
                        shown.copy(
                            tracks = shown.tracks + more.tracks,
                            positions = shown.positions + more.positions,
                            complete = more.complete,
                        )
                    } ?: shown.copy(unavailable = true)
                }
            }
            messages.loadFailed(load, shown(page.value, source.row(key).first(), null).showUnavailable, retryLoad)
            source.againOnceConnected(load, ::retry)
        }
    }

    // Cached rows are not complete, so no removal uses a position that may have moved since.
    private suspend fun paint(last: CachedPage) {
        val rows = withContext(worker) { rows(last.rows, offset = 0, complete = false, next = 0) }
        page.update {
            if (it.tracks.isNotEmpty()) {
                it
            } else {
                it.copy(
                    playlist = it.playlist ?: mapper.playlistHeader(last.item),
                    tracks = rows.tracks,
                    positions = rows.positions,
                )
            }
        }
    }

    private suspend fun rowsOrNull(load: PageLoad, offset: Int, limit: Int = MaApi.PAGE): Rows? {
        val page = load.attempt { source.playlistTracks(key, offset, limit) } ?: return null
        return withContext(worker) { rows(page.items, offset, page.complete, page.next) }
    }

    private fun rows(items: List<MediaItem>, offset: Int, complete: Boolean, next: Int) = Rows(
        items = items,
        tracks = items.map(mapper::track),
        positions = items.mapIndexed { i, track -> track.position?.toInt() ?: (offset + i + 1) },
        complete = complete,
        next = next,
    )

    private class Rows(
        val items: List<MediaItem>,
        val tracks: List<TrackUi>,
        val positions: List<Int>,
        val complete: Boolean,
        val next: Int,
    )
}

// A screen or two, so the first rows come at once; the rest follow in full pages.
internal const val FIRST_ROWS = 50

/** Later positions shift up by one, as the server's do after a removal. */
internal fun PlaylistUiState.without(position: Int): PlaylistUiState {
    val index = positions.indexOf(position).takeIf { it >= 0 } ?: return this
    return copy(
        tracks = tracks.filterIndexed { i, _ -> i != index },
        positions = positions.filterIndexed { i, _ -> i != index }.map { if (it > position) it - 1 else it },
    )
}

@Immutable
data class PlaylistUi(
    val key: String,
    val title: String,
    val art: ArtRef? = null,
    val owner: String? = null,
    val favorite: Boolean = false,
    /** The server's is_editable: false for the built-in playlists. */
    val editable: Boolean = false,
    /** The server fills it anew each time it plays ([MediaUi.dynamic]). */
    val dynamic: Boolean = false,
) {
    val media: MediaUi = MediaUi(key, MediaKind.Playlist, title, owner, art, favorite, dynamic = dynamic)
}

@Immutable
data class PlaylistUiState(
    val playlist: PlaylistUi? = null,
    val tracks: List<TrackUi> = emptyList(),
    /** 1-based playlist positions, parallel to [tracks]; a removal names one. */
    val positions: List<Int> = emptyList(),
    val currentTrackKey: String? = null,
    val loading: Boolean = false,
    /** Every page has arrived; the count, the length and removals wait for it. */
    val complete: Boolean = false,
    /** The last load failed or the playlist is gone; the page says so only when [showUnavailable]. */
    val unavailable: Boolean = false,
) {
    val showUnavailable: Boolean = unavailable && (playlist == null || tracks.isEmpty())

    val tracksLoading: Boolean = tracks.isEmpty() && (loading || playlist == null)

    val isEmpty: Boolean = playlist != null && tracks.isEmpty() && !loading && !unavailable

    // Unique lazy keys that survive a removal: a playlist can hold the same track twice.
    val rowKeys: List<String> = run {
        val copies = HashMap<String, Int>()
        tracks.map { track ->
            val copy = copies.getOrDefault(track.key, 0) + 1
            copies[track.key] = copy
            "${track.key}#$copy"
        }
    }

    val songCount: Int = tracks.size

    val durationSec: Int = tracks.sumOf { it.durationSec }

    fun positionAt(index: Int): Int = positions.getOrNull(index) ?: (index + 1)
}
