package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.orNullOnFailure
import io.github.hazymorning.amsel.app.Messages
import io.github.hazymorning.amsel.app.retryWhileOpen
import io.github.hazymorning.amsel.data.ShownPage
import io.github.hazymorning.amsel.data.library.LibraryItemEntity
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi
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

/**
 * An album page: the header from the library index and the tracks it showed last time at once,
 * then the server's album, its tracks and the artist's other albums, each shown as it arrives.
 * [playing] is the uri playing on the current speaker (or starting there).
 */
class AlbumViewModel(
    private val key: String,
    private val source: PageSource,
    private val mapper: UiMapper,
    playing: Flow<String?>,
    private val messages: Messages,
) : ViewModel() {
    private val page = MutableStateFlow(AlbumUiState(loading = true))
    private var loading: Job? = null

    val state: StateFlow<AlbumUiState> = combine(page, source.row(key), playing) { page, row, uri ->
        shown(page, row, uri)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumUiState(loading = true))

    /** The index's row paints the header until the server answers, and follows favorites changed anywhere. */
    private fun shown(page: AlbumUiState, row: LibraryItemEntity?, uri: String?): AlbumUiState {
        val album = page.album ?: row?.let(mapper::albumHeader)
        return page.copy(album = row?.let { album?.copy(favorite = it.favorite) } ?: album, currentTrackKey = uri)
    }

    private val retryLoad = retryWhileOpen(messages, ::retry)

    init {
        load(first = true)
    }

    fun retry() = load()

    /** The [first] load paints what the page showed last time while the server is asked. */
    private fun load(first: Boolean = false) {
        loading?.cancel()
        page.update { it.copy(loading = true, unavailable = false) }
        loading = viewModelScope.launch {
            val load = Load()
            val item = async { load.attempt { source.item(key) } }
            val tracks = async { load.attempt { source.albumTracks(key) } }
            launch {
                val artist = page.value.album?.artistKey ?: source.row(key).first()?.artistUri
                moreByArtist(artist ?: item.await()?.artists?.firstOrNull()?.uri)
            }
            if (first) source.shown(key)?.let { last -> page.update { it.from(last) } }
            val album = item.await()
            if (album != null) page.update { it.copy(album = mapper.albumHeader(album)) }
            val served = tracks.await()
            page.update { it.loaded(album, served) }
            messages.loadFailed(load, shown(page.value, source.row(key).first(), null).showUnavailable, retryLoad)
            if (album != null && served != null) source.keep(key, ShownPage(album, served))
            source.againOnceConnected(load, ::retry)
        }
    }

    /** What the page showed last time, where the server has not answered yet. */
    private fun AlbumUiState.from(last: ShownPage) = copy(
        album = album ?: mapper.albumHeader(last.item),
        tracks = tracks.ifEmpty { last.rows.map(mapper::track) },
    )

    private fun AlbumUiState.loaded(album: MediaItem?, tracks: List<MediaItem>?) = copy(
        tracks = tracks?.map(mapper::track) ?: this.tracks,
        loading = false,
        unavailable = album == null || tracks == null,
    )

    /** "More by" the artist: a bonus, so a failure leaves the shelf out and says nothing. */
    private suspend fun moreByArtist(artistKey: String?) {
        if (artistKey == null) return
        val albums = orNullOnFailure { source.artistAlbums(artistKey) } ?: return
        page.update { it.copy(moreByArtist = mapper.newestFirst(albums, except = key)) }
    }
}

/** What an album page shows above its tracks. */
@Immutable
data class AlbumUi(
    val key: String,
    val title: String,
    val art: ArtRef? = null,
    /** The album artist, or null when the album names none. A link when [artistKey] is set. */
    val artist: String? = null,
    val artistKey: String? = null,
    val year: Int? = null,
    val favorite: Boolean = false,
    /** Its tracks are by different artists, though the server names one ("Various Artists"). */
    val compilation: Boolean = false,
) {
    /** The album as an item, for its ⋮ sheet. */
    val media: MediaUi = MediaUi(key, MediaKind.Album, title, artist, art, favorite, artistKey = artistKey)
}

/**
 * Everything an album page draws. The library cache usually knows [album] before the server
 * answers, so the header paints at once and only the tracks wait. Until [album] is known the
 * whole page is a skeleton in the exact shape of the content.
 */
@Immutable
data class AlbumUiState(
    /** Null until known. */
    val album: AlbumUi? = null,
    /** In album order; [TrackUi.number] is the track number shown in the row. */
    val tracks: List<TrackUi> = emptyList(),
    /** Other albums by the same artist, newest first, with the year as subtitle; hidden when empty. */
    val moreByArtist: List<MediaUi> = emptyList(),
    /** The track playing on the current speaker when it is on this album; drawn highlighted. */
    val currentTrackKey: String? = null,
    /** The tracks are being fetched: the first load, or a Retry. */
    val loading: Boolean = false,
    /** The album could not be loaded or no longer exists; [showUnavailable] says whether nothing is cached. */
    val unavailable: Boolean = false,
) {
    /** No header or nothing playable to show: the page says so plainly and offers Retry. */
    val showUnavailable: Boolean = unavailable && (album == null || tracks.isEmpty())

    /** Rows stand in for the tracks while they load. */
    val tracksLoading: Boolean = tracks.isEmpty() && (loading || album == null)

    val songCount: Int = tracks.size

    val durationSec: Int = tracks.sumOf { it.durationSec }

    /**
     * A track row's secondary line: the track's artist only when it differs from the album's, so
     * guests and a compilation's artists show and the album artist is not repeated on every row.
     */
    fun subtitleOf(track: TrackUi): String? = track.artist.takeIf { it != album?.artist }
}
