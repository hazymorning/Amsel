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
import io.github.hazymorning.amsel.ui.mapping.albumBesides
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
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

/** [playing] is the URI playing on the current speaker, or starting there. */
class ArtistViewModel(
    private val key: String,
    private val source: PageSource,
    private val mapper: UiMapper,
    playing: Flow<String?>,
    private val messages: MessageController,
    private val similarOffered: Flow<Boolean> = flowOf(false),
) : ViewModel() {
    private val page = MutableStateFlow(ArtistUiState(loading = true, popularLoading = true))
    private var loading: Job? = null

    // [pageCovers] of what the page lists, for an artist without a picture: the cached page's until the
    // server's.
    private val covers = MutableStateFlow<ArtRef?>(null)

    val state: StateFlow<ArtistUiState> = combine(page, source.row(key), playing, covers) { page, row, uri, covers ->
        shown(page, row, uri, covers)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ArtistUiState(loading = true, popularLoading = true),
    )

    // The index row gives the header until the server answers, and the favourite as changed anywhere.
    // An artist without a picture of its own or in the index gets the [covers].
    private fun shown(page: ArtistUiState, row: LibraryItemEntity?, uri: String?, covers: ArtRef?): ArtistUiState {
        val artist = (page.artist ?: row?.let(mapper::media))?.let { it.copy(art = it.art ?: covers) }
        return page.copy(artist = row?.let { artist?.copy(favorite = it.favorite) } ?: artist, currentTrackKey = uri)
    }

    private val retryLoad = retryWhileOpen(messages, ::retry)

    init {
        load(first = true)
    }

    fun retry() = load()

    /** Only the [first] load shows the cached page while the server is asked. */
    private fun load(first: Boolean = false) {
        loading?.cancel()
        page.update { it.copy(loading = true, popularLoading = true, unavailable = false) }
        loading = viewModelScope.launch {
            val load = PageLoad()
            val item = async { load.attempt { source.item(key) } }
            val popular = async { load.attempt { source.artistTopTracks(key) } }
            val albums = async { load.attempt { source.artistAlbums(key) } }
            launch { similarArtists(item) }
            if (first) source.shown(key)?.let { last -> paintShown(last) }
            val albumsFromIndex = paintCached()
            // Each part shows as it answers, as popular songs are the slow call (docs/API-NOTES.md, "The
            // artist page"); the page is unavailable only once all have answered.
            val songs = launch {
                val list = popular.await()
                page.update {
                    it.copy(
                        artist = it.artist ?: list?.let(::namedIn),
                        popular = list?.map(mapper::track) ?: it.popular,
                        popularLoading = false,
                    )
                }
            }
            val discography = launch {
                albums.await()?.let { list ->
                    page.update { it.copy(artist = it.artist ?: namedIn(list), albums = mapper.newestFirst(list)) }
                }
            }
            val served = item.await()
            served?.let { artistOf(it) }?.let { artist -> page.update { it.copy(artist = artist) } }
            songs.join()
            discography.join()
            // The server's artist, else the one its albums and songs name, so an artist a music service
            // is slow to describe still gets a page.
            val artist = page.value.artist
            val servedAlbums = albums.await()
            val servedPopular = popular.await()
            if (servedAlbums != null || servedPopular != null) {
                covers.value = mapper.pageCovers(servedAlbums.orEmpty(), servedPopular.orEmpty())
            }
            val unavailable = artist == null || (servedPopular == null && servedAlbums == null)
            page.update {
                it.copy(
                    loading = false,
                    unavailable = unavailable,
                    // Index albums are dropped for an artist the server cannot give; albums an earlier
                    // visit got from the server stay.
                    albums = if (unavailable && servedAlbums == null && albumsFromIndex) emptyList() else it.albums,
                )
            }
            messages.loadFailed(load, shown(page.value, source.row(key).first(), null, null).showUnavailable, retryLoad)
            if (served != null && servedPopular != null && servedAlbums != null) {
                source.keep(key, CachedPage(served, servedPopular, servedAlbums))
            }
            source.againOnceConnected(load, ::retry)
        }
    }

    private fun namedIn(lists: List<MediaItem>): MediaUi? {
        val named = lists.firstNotNullOfOrNull { item -> item.artists.firstOrNull { it.uri == key } } ?: return null
        return MediaUi(key, MediaKind.Artist, named.name, art = mapper.art(named.cover))
    }

    // Without a picture of its own an artist gets its index row's art (its albums' covers), as in the
    // Library list.
    private suspend fun artistOf(item: MediaItem): MediaUi = mapper.media(item).let { shown ->
        shown.copy(art = shown.art ?: source.row(key).first()?.let(mapper::media)?.art)
    }

    private suspend fun paintShown(last: CachedPage) {
        val artist = artistOf(last.item)
        covers.value = mapper.pageCovers(last.shelf, last.rows)
        page.update {
            it.copy(
                artist = it.artist ?: artist,
                popular = it.popular.ifEmpty { last.rows.map(mapper::track) },
                albums = it.albums.ifEmpty { mapper.newestFirst(last.shelf) },
            )
        }
    }

    // Optional: a failure leaves the shelf out without a message. Waits until the server offers similar
    // artists, which right after a connect is not known yet. Those without a picture get their albums'
    // covers from the index.
    private suspend fun similarArtists(artist: Deferred<MediaItem?>) {
        if (similarOffered.firstOrNull { it } != true) return
        val found = orNullOnFailure { source.similarArtists(key) } ?: return
        val similar = found.similarTo(key, artist.await()).map(mapper::media)
        val covers = source.albumCovers(similar.filter { it.art == null }.map { it.key })
        val shown = similar.map { artist ->
            artist.takeIf { it.art != null } ?: artist.copy(art = mapper.albumsArt(covers[artist.key].orEmpty()))
        }
        page.update { it.copy(similar = shown) }
    }

    /** Returns true when the shown albums now come from the library index. */
    private suspend fun paintCached(): Boolean {
        if (page.value.albums.isNotEmpty()) return false
        val rows = source.cachedAlbums(key)
        if (rows.isEmpty()) return false
        page.update { it.copy(albums = it.albums.ifEmpty { mapper.newestFirstRows(rows) }) }
        return true
    }
}

@Immutable
data class ArtistUiState(
    val artist: MediaUi? = null,
    val popular: List<TrackUi> = emptyList(),
    val albums: List<MediaUi> = emptyList(),
    /** In the server's order; never what makes a page available. */
    val similar: List<MediaUi> = emptyList(),
    val currentTrackKey: String? = null,
    val loading: Boolean = false,
    val popularLoading: Boolean = false,
    /** The last load failed or the artist is gone; the page says so only when [showUnavailable]. */
    val unavailable: Boolean = false,
) {

    val showUnavailable: Boolean = unavailable && (artist == null || (popular.isEmpty() && albums.isEmpty()))

    val contentLoading: Boolean = popular.isEmpty() && albums.isEmpty() && (loading || artist == null)

    val popularPending: Boolean = popularLoading && popular.isEmpty() && !contentLoading

    // The song's album; for a single, its artists unless they are just this artist, which would say
    // nothing on its own page.
    fun subtitleOf(track: TrackUi): String? = albumBesides(track.title, track.album)
        ?: track.artist.takeUnless { it.equals(artist?.title, ignoreCase = true) }
}
