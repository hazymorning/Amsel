package io.github.hazymorning.amsel.ui.screens.search

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.SearchResults
import io.github.hazymorning.amsel.playback.failureText
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Search as you type: the field updates at once, the server is asked 300 ms after the last
 * keystroke, and only the latest query's answer is shown. The library index adds what a typo kept
 * the server from finding ([Fuzzy]), and answers alone while the server cannot. The query survives
 * process death.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val handle: SavedStateHandle,
    private val search: suspend (String) -> SearchResults,
    private val mapper: UiMapper,
    /** The uri playing on the current speaker (or starting there). */
    playing: Flow<String?>,
    /** The library index, again whenever it changes: forgiving matches, and the covers its artists wear. */
    library: Flow<List<MediaUi>> = flowOf(emptyList()),
    /** Where matching runs, off the main thread; a test passes its own scheduler. */
    private val background: CoroutineContext = Dispatchers.Default,
) : ViewModel() {
    /**
     * The library in words, and its artists' covers: made while the screen shows or a search waits
     * for it, again with each change of the library, and let go soon after. A playing book writes its
     * progress to the index every 30 s, which Search left behind must not read again; a search after
     * coming back waits for the index as it is then.
     */
    private val index: StateFlow<SearchIndex?> =
        library.conflate()
            .runningFold<List<MediaUi>, SearchIndex?>(null) { last, items -> SearchIndex.of(items, last) }
            .flowOn(background)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)
    private val query = handle.getStateFlow(QUERY, "")
    private val retries = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Kept for the ViewModel's life, so the results are still there when the tab comes back. */
    private val progress = MutableStateFlow(SearchProgress())

    // The index shows nothing itself: collected with the screen, it is ready by the first search.
    val state: StateFlow<SearchUiState> =
        combine(query, progress, playing, index) { q, p, uri, _ -> p.toUiState(q).copy(playingKey = uri) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), progress.value.toUiState(query.value))

    init {
        viewModelScope.launch {
            searches().collect { event -> progress.update { it.reduce(event) } }
        }
    }

    fun onQueryChange(text: String) {
        handle[QUERY] = text
    }

    /** Asks again for the current query, without waiting. */
    fun retry() {
        retries.tryEmit(query.value.trim())
    }

    // Clearing the field cancels at once; flatMapLatest drops any answer to an older query.
    private fun searches(): Flow<SearchEvent> = merge(
        query.debounce { if (it.isBlank()) 0L else DEBOUNCE_MS }.map { it.trim() }.distinctUntilChanged(),
        retries,
    ).flatMapLatest { q ->
        if (q.isEmpty()) {
            flowOf<SearchEvent>(SearchEvent.Cleared)
        } else {
            flow<SearchEvent> {
                emit(SearchEvent.Started(q))
                emit(answer(q))
            }
        }
    }

    private suspend fun answer(q: String): SearchEvent {
        // The first search after coming back waits for the index.
        val ready = index.filterNotNull().first()
        val near = withContext(background) { ready.near(Fuzzy.words(q), NEAR_MATCHES) }
        return try {
            val raw = search(q)
            SearchEvent.Found(searchResults(q, raw, mapper, ready.covers).withNear(near))
        } catch (e: MaConnectionException) {
            // The library answers alone while the server cannot.
            if (near.isNotEmpty()) {
                SearchEvent.Found(
                    SearchResultsUi(q).withNear(near),
                )
            } else {
                SearchEvent.Failed(q, failureText(e))
            }
        } catch (e: MaCommandException) {
            SearchEvent.Failed(q, R.string.search_failed)
        }
    }

    private companion object {
        const val QUERY = "query"
        const val DEBOUNCE_MS = 300L
        const val NEAR_MATCHES = 12
    }
}

internal sealed interface SearchEvent {
    /** The field was emptied: forget the last answer. */
    data object Cleared : SearchEvent

    data class Started(val query: String) : SearchEvent

    data class Found(val results: SearchResultsUi) : SearchEvent

    /** It could not be asked, or answered with an error: [failure] says which. */
    data class Failed(val query: String, @StringRes val failure: Int) : SearchEvent
}

/** Where searching stands, apart from the field itself. */
internal data class SearchProgress(
    val results: SearchResultsUi? = null,
    /** The query being searched right now. */
    val running: String? = null,
    /** The query the last finished search was for. */
    val answered: String? = null,
    /** Why the last search has no answer; its results are for no query now shown. */
    @StringRes val failure: Int? = null,
) {
    fun reduce(event: SearchEvent): SearchProgress = when (event) {
        SearchEvent.Cleared -> SearchProgress()
        is SearchEvent.Started -> copy(running = event.query)
        is SearchEvent.Found -> SearchProgress(event.results, answered = event.results.query)
        is SearchEvent.Failed -> SearchProgress(answered = event.query, failure = event.failure)
    }

    /** The field shows [query] as typed; until its answer arrives, the field's glyph is the busy spinner. */
    fun toUiState(query: String) = SearchUiState(
        query = query,
        searching = query.isNotBlank() && (running != null || answered != query.trim()),
        results = results,
        failure = failure,
    )
}

/**
 * The screen's sections from the server's lists (library matches already first in each). The top
 * result is the item named exactly as typed (artists, then albums, playlists, radio stations,
 * books, podcasts and songs); else the first library artist or album; else the first song. It is
 * not repeated in its own section. An artist without a picture wears its albums' covers ([listed]):
 * [held] are those the library index made, by uri.
 */
internal fun searchResults(
    query: String,
    raw: SearchResults,
    mapper: UiMapper,
    held: Map<String, ArtRef> = emptyMap(),
): SearchResultsUi {
    val exact = sequenceOf(raw.artists, raw.albums, raw.playlists, raw.radio, raw.audiobooks, raw.podcasts, raw.tracks)
        .flatten()
        .firstOrNull { it.name.trim().equals(query, ignoreCase = true) }
    val top = exact
        ?: raw.artists.firstOrNull { it.isLibraryItem }
        ?: raw.albums.firstOrNull { it.isLibraryItem }
        ?: raw.tracks.firstOrNull()

    fun List<MediaItem>.rest() = if (top == null) this else filter { it.uri != top.uri }
    fun MediaItem.shown() = mapper.listed(this, raw, held)
    return SearchResultsUi(
        query = query,
        top = top?.let { if (it in raw.tracks) TopResult.Song(mapper.track(it)) else TopResult.Item(it.shown()) },
        songs = raw.tracks.rest().map(mapper::track),
        albums = raw.albums.rest().map(mapper::media),
        artists = raw.artists.rest().map { it.shown() },
        playlists = raw.playlists.rest().map(mapper::media),
        radio = raw.radio.rest().map(mapper::media),
        audiobooks = raw.audiobooks.rest().map(mapper::media),
        podcasts = raw.podcasts.rest().map(mapper::media),
    )
}

/**
 * These results with the library's near matches the server did not return, each after its kind's
 * own; with no top result, the closest of them stands in.
 */
internal fun SearchResultsUi.withNear(near: List<MediaUi>): SearchResultsUi {
    if (near.isEmpty()) return this
    val topKey = when (val t = top) {
        is TopResult.Item -> t.item.key
        is TopResult.Song -> t.track.key
        null -> null
    }
    val shown =
        (albums + artists + playlists + radio + audiobooks + podcasts).map { it.key }.toSet() + listOfNotNull(topKey)
    val extra = near.filter { it.key !in shown }
    if (extra.isEmpty()) return this
    val newTop = if (top == null) extra.first() else null
    val rest = extra.filter { it != newTop }
    fun List<MediaUi>.plus(kind: MediaKind) = this + rest.filter { it.kind == kind }
    return copy(
        top = top ?: newTop?.let(TopResult::Item),
        albums = albums.plus(MediaKind.Album),
        artists = artists.plus(MediaKind.Artist),
        playlists = playlists.plus(MediaKind.Playlist),
        radio = radio.plus(MediaKind.Radio),
        audiobooks = audiobooks.plus(MediaKind.Audiobook),
        podcasts = podcasts.plus(MediaKind.Podcast),
    )
}

/** The one best match, which may be any kind. A song or a station plays when tapped; anything else opens. */
@Immutable
sealed interface TopResult {
    @Immutable data class Song(val track: TrackUi) : TopResult

    @Immutable data class Item(val item: MediaUi) : TopResult
}

/**
 * The answer to [query]. Within each section the data layer puts library matches first; the screen
 * shows the sections in this order and hides empty ones.
 */
@Immutable
data class SearchResultsUi(
    val query: String,
    val top: TopResult? = null,
    val songs: List<TrackUi> = emptyList(),
    val albums: List<MediaUi> = emptyList(),
    val artists: List<MediaUi> = emptyList(),
    val playlists: List<MediaUi> = emptyList(),
    /** Radio stations play when tapped; they have no page. */
    val radio: List<MediaUi> = emptyList(),
    val audiobooks: List<MediaUi> = emptyList(),
    val podcasts: List<MediaUi> = emptyList(),
) {
    val isEmpty: Boolean
        get() = top == null && songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty() &&
            radio.isEmpty() && audiobooks.isEmpty() && podcasts.isEmpty()
}

@Immutable
data class SearchUiState(
    /** What is in the field. Update it synchronously on every keystroke, or the cursor jumps. */
    val query: String = "",
    /** A search for [query] is on its way; the previous [results] stay until it answers. */
    val searching: Boolean = false,
    /** The latest answer, possibly for an earlier query; null until the first one arrives. */
    val results: SearchResultsUi? = null,
    /** Why the last search failed, said in place of its results. */
    @StringRes val failure: Int? = null,
    /** The song or radio station playing on the current speaker; its row shows the playing mark. */
    val playingKey: String? = null,
)
