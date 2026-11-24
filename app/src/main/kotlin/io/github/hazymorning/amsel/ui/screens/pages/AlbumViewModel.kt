package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.orNullOnFailure
import io.github.hazymorning.amsel.app.MessageController
import io.github.hazymorning.amsel.app.retryWhileOpen
import io.github.hazymorning.amsel.data.CachedPage
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

/** [playing] is the URI playing on the current speaker, or starting there. */
class AlbumViewModel(
    private val key: String,
    private val source: PageSource,
    private val mapper: UiMapper,
    playing: Flow<String?>,
    private val messages: MessageController,
) : ViewModel() {
    private val page = MutableStateFlow(AlbumUiState(loading = true))
    private var loading: Job? = null

    val state: StateFlow<AlbumUiState> = combine(page, source.row(key), playing) { page, row, uri ->
        shown(page, row, uri)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumUiState(loading = true))

    // The index row gives the header until the server answers, and the favourite as changed anywhere.
    private fun shown(page: AlbumUiState, row: LibraryItemEntity?, uri: String?): AlbumUiState {
        val album = page.album ?: row?.let(mapper::albumHeader)
        return page.copy(album = row?.let { album?.copy(favorite = it.favorite) } ?: album, currentTrackKey = uri)
    }

    private val retryLoad = retryWhileOpen(messages, ::retry)

    init {
        load(first = true)
    }

    fun retry() = load()

    /** Only the [first] load shows the cached page while the server is asked. */
    private fun load(first: Boolean = false) {
        loading?.cancel()
        page.update { it.copy(loading = true, unavailable = false) }
        loading = viewModelScope.launch {
            val load = PageLoad()
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
            if (album != null && served != null) source.keep(key, CachedPage(album, served))
            source.againOnceConnected(load, ::retry)
        }
    }

    private fun AlbumUiState.from(last: CachedPage) = copy(
        album = album ?: mapper.albumHeader(last.item),
        tracks = tracks.ifEmpty { last.rows.map(mapper::track) },
    )

    private fun AlbumUiState.loaded(album: MediaItem?, tracks: List<MediaItem>?) = copy(
        tracks = tracks?.map(mapper::track) ?: this.tracks,
        loading = false,
        unavailable = album == null || tracks == null,
    )

    // Optional: a failure leaves the shelf out without a message.
    private suspend fun moreByArtist(artistKey: String?) {
        if (artistKey == null) return
        val albums = orNullOnFailure { source.artistAlbums(artistKey) } ?: return
        page.update { it.copy(moreByArtist = mapper.newestFirst(albums, except = key)) }
    }
}

@Immutable
data class AlbumUi(
    val key: String,
    val title: String,
    val art: ArtRef? = null,
    val artist: String? = null,
    val artistKey: String? = null,
    val year: Int? = null,
    val favorite: Boolean = false,
    /** Its tracks are by different artists, though the server names one ("Various Artists"). */
    val compilation: Boolean = false,
) {
    val media: MediaUi = MediaUi(key, MediaKind.Album, title, artist, art, favorite, artistKey = artistKey)
}

@Immutable
data class AlbumUiState(
    val album: AlbumUi? = null,
    val tracks: List<TrackUi> = emptyList(),
    val moreByArtist: List<MediaUi> = emptyList(),
    val currentTrackKey: String? = null,
    val loading: Boolean = false,
    /** The last load failed or the album is gone; the page says so only when [showUnavailable]. */
    val unavailable: Boolean = false,
) {
    val showUnavailable: Boolean = unavailable && (album == null || tracks.isEmpty())

    val tracksLoading: Boolean = tracks.isEmpty() && (loading || album == null)

    val songCount: Int = tracks.size

    val durationSec: Int = tracks.sumOf { it.durationSec }

    // The track's artist only where it differs from the album's: guests and a compilation's artists
    // show, and the album artist is not repeated on every row.
    fun subtitleOf(track: TrackUi): String? = track.artist.takeIf { it != album?.artist }
}
