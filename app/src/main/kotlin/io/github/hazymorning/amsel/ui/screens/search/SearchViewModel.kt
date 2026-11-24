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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * The library index adds matches a typo kept the server from finding ([FuzzyMatcher]), and answers
 * alone while the server is unreachable.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val handle: SavedStateHandle,
    private val search: suspend (String) -> SearchResults,
    private val mapper: UiMapper,
    playing: Flow<String?>,
    library: Flow<List<MediaUi>> = flowOf(emptyList()),
    private val background: CoroutineContext = Dispatchers.Default,
) : ViewModel() {
    /**
     * Built only while collected: a playing book rewrites the index every 30 s (docs/API-NOTES.md,
     * "Audiobooks and podcasts"), which a Search left behind must not process. replayExpirationMillis = 0
     * drops the stale index, so a search after coming back waits for the current one.
     */
    private val index: StateFlow<SearchIndex?> =
        library.conflate()
            .runningFold<List<MediaUi>, SearchIndex?>(null) { last, items -> SearchIndex.of(items, last) }
            .flowOn(background)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)
    private val query = handle.getStateFlow(QUERY, "")
    private val retries = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val progress = MutableStateFlow(SearchProgress())

    // index is combined only to keep it collected while the screen shows, so it is built by the first search.
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

    fun retry() {
        retries.tryEmit(query.value.trim())
    }

    // A blank query skips the debounce, so clearing cancels at once; flatMapLatest drops stale answers.
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

    private suspend fun answer(q: String): SearchEvent = coroutineScope {
        // Runs beside the server's search; after coming back it first waits for the rebuilt index.
        val library = async {
            val ready = index.filterNotNull().first()
            ready to withContext(background) { ready.near(FuzzyMatcher.words(q), NEAR_MATCHES) }
        }
        try {
            val raw = search(q)
            val (ready, near) = library.await()
            SearchEvent.Found(searchResults(q, raw, mapper, ready.covers).withNear(near))
        } catch (e: MaConnectionException) {
            // Unreachable server: the library's matches alone, if there are any.
            val near = library.await().second
            if (near.isNotEmpty()) {
                SearchEvent.Found(
                    SearchResultsUi(q).withNear(near),
                )
            } else {
                SearchEvent.Failed(q, failureText(e))
            }
        } catch (e: MaCommandException) {
            library.cancel()
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
    data object Cleared : SearchEvent

    data class Started(val query: String) : SearchEvent

    data class Found(val results: SearchResultsUi) : SearchEvent

    data class Failed(val query: String, @StringRes val failure: Int) : SearchEvent
}

internal data class SearchProgress(
    val results: SearchResultsUi? = null,
    val running: String? = null,
    val answered: String? = null,
    @StringRes val failure: Int? = null,
) {
    fun reduce(event: SearchEvent): SearchProgress = when (event) {
        SearchEvent.Cleared -> SearchProgress()
        is SearchEvent.Started -> copy(running = event.query)
        is SearchEvent.Found -> SearchProgress(event.results, answered = event.results.query)
        is SearchEvent.Failed -> SearchProgress(answered = event.query, failure = event.failure)
    }

    /** [SearchUiState.searching] covers the debounce too: it stays true until [query] itself is answered. */
    fun toUiState(query: String) = SearchUiState(
        query = query,
        searching = query.isNotBlank() && (running != null || answered != query.trim()),
        results = results,
        failure = failure,
    )
}

/** [held]: the index's covers for artists sent without a picture, by uri ([listed]). */
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

/** [near] is best first, so without a top result the closest new match becomes it. */
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

@Immutable
sealed interface TopResult {
    @Immutable data class Song(val track: TrackUi) : TopResult

    @Immutable data class Item(val item: MediaUi) : TopResult
}

/** Per list: the server's matches, library ones first (re-sorted by MaApi.search), then the index's near matches. */
@Immutable
data class SearchResultsUi(
    val query: String,
    val top: TopResult? = null,
    val songs: List<TrackUi> = emptyList(),
    val albums: List<MediaUi> = emptyList(),
    val artists: List<MediaUi> = emptyList(),
    val playlists: List<MediaUi> = emptyList(),
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
    /** Must update synchronously on every keystroke, or the field's cursor jumps. */
    val query: String = "",
    val searching: Boolean = false,
    /** May answer an earlier query while [searching]; null after a clear or a failure. */
    val results: SearchResultsUi? = null,
    @StringRes val failure: Int? = null,
    val playingKey: String? = null,
)
