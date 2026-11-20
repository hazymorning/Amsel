package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.app.Messages
import io.github.hazymorning.amsel.app.PlaylistEdit
import io.github.hazymorning.amsel.app.retryWhileOpen
import io.github.hazymorning.amsel.data.ShownPage
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
 * A playlist page: the header from the library index and the first tracks it showed last time at
 * once, then the server's playlist and its tracks with their positions (what removing one names):
 * the first page as soon as it comes, the rest under it. [playing] is the uri playing on the
 * current speaker (or starting there); [edits] are what the ⋮ sheet did to this playlist.
 */
class PlaylistViewModel(
    private val key: String,
    private val source: PageSource,
    private val mapper: UiMapper,
    playing: Flow<String?>,
    edits: Flow<PlaylistEdit>,
    private val messages: Messages,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val page = MutableStateFlow(PlaylistUiState(loading = true))
    private var loading: Job? = null

    val state: StateFlow<PlaylistUiState> = combine(page, source.row(key), playing) { page, row, uri ->
        shown(page, row, uri)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistUiState(loading = true))

    /** The index's row paints the header until the server answers, and follows favorites changed anywhere. */
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

                    // What was added shows once the playlist is read again; its rows stay meanwhile.
                    is PlaylistEdit.Added -> load()

                    // The rows no longer show what the server holds: they load again, and a
                    // failed load says so instead of keeping the trimmed list.
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

    /** The track at playlist [position] was removed: its row goes at once, the ones after move up. */
    fun removed(position: Int) = page.update { it.without(position) }

    /** The [first] load paints what the page showed last time while the server is asked. */
    private fun load(first: Boolean = false) {
        loading?.cancel()
        page.update { it.copy(loading = true, unavailable = false) }
        loading = viewModelScope.launch {
            val load = Load()
            val item = async { load.attempt { source.item(key) } }
            val firstPage = async { rowsOrNull(load, offset = 0, limit = FIRST_ROWS) }
            if (first) source.shown(key)?.let { last -> paint(last) }
            val playlist = item.await()
            if (playlist != null) page.update { it.copy(playlist = mapper.playlistHeader(playlist)) }
            var rows = firstPage.await()
            val firstRows = rows
            if (playlist != null && firstRows != null) launch { source.keep(key, ShownPage(playlist, firstRows.items)) }
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

    /**
     * The first tracks the page showed last time, where the server has not answered yet. They are
     * not [PlaylistUiState.complete], so nothing is removed by a position that may have moved since.
     */
    private suspend fun paint(last: ShownPage) {
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

    /** A page of rows from [offset], mapped off the main thread; null when the server could not be asked or refused. */
    private suspend fun rowsOrNull(load: Load, offset: Int, limit: Int = MaApi.PAGE): Rows? {
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

/** The first rows, a screen or two: they come at once, and the rest in pages under them. */
internal const val FIRST_ROWS = 50

/** The state after the server removes [position]: later positions shift up by one, as the server's do. */
internal fun PlaylistUiState.without(position: Int): PlaylistUiState {
    val index = positions.indexOf(position).takeIf { it >= 0 } ?: return this
    return copy(
        tracks = tracks.filterIndexed { i, _ -> i != index },
        positions = positions.filterIndexed { i, _ -> i != index }.map { if (it > position) it - 1 else it },
    )
}

/** What a playlist page shows above its tracks. */
@Immutable
data class PlaylistUi(
    val key: String,
    val title: String,
    val art: ArtRef? = null,
    /** Who made it, such as "Anna" or "Music Assistant" for its built-in playlists. */
    val owner: String? = null,
    val favorite: Boolean = false,
    /** The person may remove tracks from it (their own playlists, not the built-in ones). */
    val editable: Boolean = false,
    /** The server fills it anew each time it plays ([MediaUi.dynamic]). */
    val dynamic: Boolean = false,
) {
    /** The playlist as an item, for its ⋮ sheet. */
    val media: MediaUi = MediaUi(key, MediaKind.Playlist, title, owner, art, favorite, dynamic = dynamic)
}

/**
 * Everything a playlist page draws. Like an album page, the header paints from the library cache
 * and only the tracks wait; until [playlist] is known the whole page is a skeleton.
 */
@Immutable
data class PlaylistUiState(
    /** Null until known. */
    val playlist: PlaylistUi? = null,
    /** In playlist order: the first page, then the rest as they arrive. */
    val tracks: List<TrackUi> = emptyList(),
    /** Each track's 1-based position in the playlist, parallel to [tracks]: what removing one names. */
    val positions: List<Int> = emptyList(),
    /** The track playing on the current speaker when it is in this playlist; drawn highlighted. */
    val currentTrackKey: String? = null,
    /** The tracks are being fetched: the first load, a Retry, or after a change the server refused. */
    val loading: Boolean = false,
    /** Every page has arrived, which the count, the length and removing a track need. */
    val complete: Boolean = false,
    /** The playlist could not be loaded or no longer exists; [showUnavailable] says whether nothing is cached. */
    val unavailable: Boolean = false,
) {
    /** No header or nothing playable to show: the page says so plainly and offers Retry. */
    val showUnavailable: Boolean = unavailable && (playlist == null || tracks.isEmpty())

    /** Rows stand in for the tracks while they load. */
    val tracksLoading: Boolean = tracks.isEmpty() && (loading || playlist == null)

    /** Loaded, and nothing in it: one plain line instead of tracks, and no Play or Shuffle. */
    val isEmpty: Boolean = playlist != null && tracks.isEmpty() && !loading && !unavailable

    /** One per row, kept through a removal: the track, and which copy of it (a playlist can hold one twice). */
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

    /** The playlist position of the row at [index]. */
    fun positionAt(index: Int): Int = positions.getOrNull(index) ?: (index + 1)
}
