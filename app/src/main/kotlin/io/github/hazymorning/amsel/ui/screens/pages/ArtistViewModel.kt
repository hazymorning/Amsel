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
import io.github.hazymorning.amsel.ui.mapping.albumBesides
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * An artist page: name, photo and albums from the library index, or all it showed last time, at
 * once, then the server's artist, popular songs and albums, each shown the moment it arrives.
 * Popular songs are the slow part (the server may ask music services for them), so nothing waits
 * for them, nor for the similar artists, which are asked once [similarOffered] says the server can
 * find any: right after a connect it has not said so yet.
 * [playing] is the uri playing on the current speaker (or starting there).
 */
class ArtistViewModel(
    private val key: String,
    private val source: PageSource,
    private val mapper: UiMapper,
    playing: Flow<String?>,
    private val messages: Messages,
    private val similarOffered: Flow<Boolean> = flowOf(false),
) : ViewModel() {
    private val page = MutableStateFlow(ArtistUiState(loading = true, popularLoading = true))
    private var loading: Job? = null

    val state: StateFlow<ArtistUiState> = combine(page, source.row(key), playing) { page, row, uri ->
        shown(page, row, uri)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ArtistUiState(loading = true, popularLoading = true),
    )

    /** The index's row paints the header until the server answers, and follows favorites changed anywhere. */
    private fun shown(page: ArtistUiState, row: LibraryItemEntity?, uri: String?): ArtistUiState {
        val artist = page.artist ?: row?.let(mapper::media)
        return page.copy(artist = row?.let { artist?.copy(favorite = it.favorite) } ?: artist, currentTrackKey = uri)
    }

    private val retryLoad = retryWhileOpen(messages, ::retry)

    init {
        load(first = true)
    }

    fun retry() = load()

    /** The [first] load paints what the page showed last time while the server is asked. */
    private fun load(first: Boolean = false) {
        loading?.cancel()
        page.update { it.copy(loading = true, popularLoading = true, unavailable = false) }
        loading = viewModelScope.launch {
            val load = Load()
            val item = async { load.attempt { source.item(key) } }
            val popular = async { load.attempt { source.artistTopTracks(key) } }
            val albums = async { load.attempt { source.artistAlbums(key) } }
            launch { similarArtists(item) }
            if (first) source.shown(key)?.let { last -> paintShown(last) }
            val albumsFromIndex = paintCached()
            // Each part lands on its own; the page is unavailable only once all have answered.
            val songs = launch {
                val list = popular.await()
                page.update { it.copy(popular = list?.map(mapper::track) ?: it.popular, popularLoading = false) }
            }
            val discography = launch {
                albums.await()?.let { list -> page.update { it.copy(albums = mapper.newestFirst(list)) } }
            }
            val served = item.await()
            val artist = served?.let { artistOf(it) }
            if (artist != null) page.update { it.copy(artist = artist) }
            songs.join()
            discography.join()
            val servedAlbums = albums.await()
            // Popular songs or albums alone still make a page; neither, or no artist, does not.
            val unavailable = artist == null || (popular.await() == null && servedAlbums == null)
            page.update {
                it.copy(
                    loading = false,
                    unavailable = unavailable,
                    // The index's albums of an artist the server cannot give are not shown as if it
                    // could; what an earlier visit got from the server stays.
                    albums = if (unavailable && servedAlbums == null && albumsFromIndex) emptyList() else it.albums,
                )
            }
            messages.loadFailed(load, shown(page.value, source.row(key).first(), null).showUnavailable, retryLoad)
            val servedPopular = popular.await()
            if (served != null && servedPopular != null && servedAlbums != null) {
                source.keep(key, ShownPage(served, servedPopular, servedAlbums))
            }
        }
    }

    /** Without a picture of its own an artist wears its albums, as in the Library's list. */
    private suspend fun artistOf(item: MediaItem): MediaUi = mapper.media(item).let { shown ->
        shown.copy(art = shown.art ?: source.row(key).first()?.let(mapper::media)?.art)
    }

    /** What the page showed last time, where the server has not answered yet. */
    private suspend fun paintShown(last: ShownPage) {
        val artist = artistOf(last.item)
        page.update {
            it.copy(
                artist = it.artist ?: artist,
                popular = it.popular.ifEmpty { last.rows.map(mapper::track) },
                albums = it.albums.ifEmpty { mapper.newestFirst(last.shelf) },
            )
        }
    }

    /** Similar artists: a bonus, so a failure or none leaves the shelf out and says nothing. */
    private suspend fun similarArtists(artist: Deferred<MediaItem?>) {
        if (similarOffered.firstOrNull { it } != true) return
        val found = orNullOnFailure { source.similarArtists(key) } ?: return
        val similar = found.similarTo(key, artist.await()).map(mapper::media)
        page.update { it.copy(similar = similar) }
    }

    /** Albums from the library index, unless the page already shows them. True when they now come from it. */
    private suspend fun paintCached(): Boolean {
        if (page.value.albums.isNotEmpty()) return false
        val rows = source.cachedAlbums(key)
        if (rows.isEmpty()) return false
        page.update { it.copy(albums = it.albums.ifEmpty { mapper.newestFirstRows(rows) }) }
        return true
    }
}

/**
 * Everything an artist page draws: photo and name, Play and Shuffle, five popular songs as the
 * server ranks them, the artist's albums, then a shelf of similar artists. The header and the
 * albums paint from the library cache; until [artist] is known the whole page is a skeleton.
 * Popular songs often arrive last: while [popularLoading], their place holds skeleton rows so the
 * albums do not move.
 */
@Immutable
data class ArtistUiState(
    /** Name and photo (kind Artist). Null until known. */
    val artist: MediaUi? = null,
    /** The server's top tracks for this artist; the page shows the first five. */
    val popular: List<TrackUi> = emptyList(),
    /** Every album by the artist, newest first, with the year as subtitle. */
    val albums: List<MediaUi> = emptyList(),
    /** Artists the server finds similar, in its order; hidden when empty, and never what makes a page. */
    val similar: List<MediaUi> = emptyList(),
    /** The track playing on the current speaker; drawn highlighted when it is among [popular]. */
    val currentTrackKey: String? = null,
    /** Songs and albums are being fetched: the first load, or a Retry. */
    val loading: Boolean = false,
    /** The popular songs have not answered yet (the albums may already show). */
    val popularLoading: Boolean = false,
    /** The artist could not be loaded or no longer exists; [showUnavailable] says whether nothing is cached. */
    val unavailable: Boolean = false,
) {

    /** No header or nothing to show: the page says so plainly and offers Retry. */
    val showUnavailable: Boolean = unavailable && (artist == null || (popular.isEmpty() && albums.isEmpty()))

    /** Skeleton rows and tiles stand in for songs and albums while they load. */
    val contentLoading: Boolean = popular.isEmpty() && albums.isEmpty() && (loading || artist == null)

    /** Skeleton rows stand in for the popular songs above albums that are already shown. */
    val popularPending: Boolean = popularLoading && popular.isEmpty() && !contentLoading

    /**
     * A popular song's album; for a single, which names no album, its artists when
     * they are not just this artist (on their own page that line would say nothing), else the title alone.
     */
    fun subtitleOf(track: TrackUi): String? = albumBesides(track.title, track.album)
        ?: track.artist.takeUnless { it.equals(artist?.title, ignoreCase = true) }
}
