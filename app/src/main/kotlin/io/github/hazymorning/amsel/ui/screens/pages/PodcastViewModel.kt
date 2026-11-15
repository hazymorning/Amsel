package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hazymorning.amsel.api.EPISODE_PAGE
import io.github.hazymorning.amsel.api.ItemPage
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.listening
import io.github.hazymorning.amsel.api.orNullOnFailure
import io.github.hazymorning.amsel.app.Messages
import io.github.hazymorning.amsel.app.retryWhileOpen
import io.github.hazymorning.amsel.data.ShownPage
import io.github.hazymorning.amsel.data.library.LibraryItemEntity
import io.github.hazymorning.amsel.data.library.ProgressUpdate
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaUi
import java.time.LocalDate
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
 * A podcast page: the header from the library index and the newest episodes it showed last time at
 * once, then the server's podcast and its newest episodes, a page at a time as the list nears its
 * end. Each episode follows the person's progress as they listen, finish or mark it.
 */
class PodcastViewModel(
    private val key: String,
    private val source: PageSource,
    private val mapper: UiMapper,
    playing: Flow<String?>,
    private val messages: Messages,
) : ViewModel() {
    private val page = MutableStateFlow(PodcastUiState(loading = true))
    private var loading: Job? = null
    private var more: Job? = null

    /** Where the server's next page of episodes starts; null until its first page is in. */
    private var next: Int? = null

    val state: StateFlow<PodcastUiState> = combine(page, source.row(key), playing) { page, row, uri ->
        shown(page, row, uri)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastUiState(loading = true))

    /** The index's row paints the header until the server answers, and follows favorites changed anywhere. */
    private fun shown(page: PodcastUiState, row: LibraryItemEntity?, uri: String?): PodcastUiState {
        val podcast = page.podcast ?: row?.let(mapper::media)
        return page.copy(podcast = row?.let { podcast?.copy(favorite = it.favorite) } ?: podcast, currentKey = uri)
    }

    private val retryLoad = retryWhileOpen(messages, ::retry)

    init {
        load(first = true)
        viewModelScope.launch { source.progress.collect { update -> page.update { it.withProgress(update) } } }
    }

    fun retry() = load()

    /**
     * The next page of episodes, once the list nears its end; one at a time, never during a reload
     * (it would count from the old list), and not again after a failure until Retry.
     */
    fun loadMore() {
        val shown = page.value
        if (shown.complete || shown.loading || shown.moreFailed || shown.episodes.isEmpty()) return
        if (more?.isActive == true || loading?.isActive == true) return
        // Episodes shown from last time, before the server's first page, carry on from nowhere yet.
        val from = next ?: return
        more = viewModelScope.launch {
            page.update { it.copy(loadingMore = true) }
            val more = episodesOrNull(from)
            if (more != null) next = more.next
            page.update {
                if (more == null) {
                    it.copy(loadingMore = false, moreFailed = true)
                } else {
                    val episodes = episodesAfter(it.episodes, more.items)
                    // A page that adds nothing new would only be asked for again.
                    it.copy(
                        episodes = episodes,
                        loadingMore = false,
                        complete = more.complete || episodes.size == it.episodes.size,
                    )
                }
            }
        }
    }

    fun retryMore() {
        page.update { it.copy(moreFailed = false) }
        loadMore()
    }

    /** The [first] load paints what the page showed last time while the server is asked. */
    private fun load(first: Boolean = false) {
        loading?.cancel()
        more?.cancel()
        page.update { it.copy(loading = true, unavailable = false) }
        loading = viewModelScope.launch {
            val load = Load()
            val podcast = async { load.attempt { source.item(key) } }
            val newest = async { load.attempt { source.episodes(key, 0, EPISODE_PAGE) } }
            if (first) source.shown(key)?.let { last -> page.update { it.from(last) } }
            val episodes = newest.await()
            // A failed reload keeps the list it had, and with it where that list goes on.
            if (episodes != null) next = episodes.next
            val item = podcast.await()
            page.update {
                it.copy(
                    podcast = item?.let(mapper::media) ?: it.podcast,
                    episodes = episodes?.items?.let { items -> episodesAfter(emptyList(), items) } ?: it.episodes,
                    complete = episodes?.complete ?: it.complete,
                    loading = false,
                    loadingMore = false,
                    unavailable = item == null || episodes == null,
                    moreFailed = false,
                )
            }
            messages.loadFailed(load, shown(page.value, source.row(key).first(), null).showUnavailable, retryLoad)
            if (item != null && episodes != null) source.keep(key, ShownPage(item, episodes.items))
        }
    }

    /** What the page showed last time, where the server has not answered yet. */
    private fun PodcastUiState.from(last: ShownPage) = copy(
        podcast = podcast ?: mapper.media(last.item),
        episodes = episodes.ifEmpty { episodesAfter(emptyList(), last.rows) },
    )

    /** [items] after [shown], each episode once: a feed may repeat one, and a row's key may not repeat. */
    private fun episodesAfter(shown: List<EpisodeUi>, items: List<MediaItem>) =
        (shown + items.map(mapper::episode)).distinctBy { it.key }

    /** A page of episodes from [offset]; null when the server could not be asked or refused. */
    private suspend fun episodesOrNull(offset: Int): ItemPage<MediaItem>? =
        orNullOnFailure { source.episodes(key, offset, EPISODE_PAGE) }
}

/** The episode [update] names moved on, finished or was marked; the others stay as they are. */
internal fun PodcastUiState.withProgress(update: ProgressUpdate): PodcastUiState {
    if (episodes.none { it.key == update.uri }) return this
    return copy(
        episodes = episodes.map { episode ->
            if (episode.key != update.uri) {
                episode
            } else {
                val where = listening(episode.durationSec, update.positionMs, update.fullyPlayed)
                episode.copy(played = update.fullyPlayed, resumeMs = update.positionMs, remainingSec = where?.second)
            }
        },
    )
}

/** One episode in a podcast's list. */
@Immutable
data class EpisodeUi(
    val key: String,
    val title: String,
    val date: LocalDate? = null,
    val durationSec: Int? = null,
    /** Where the person stopped in it, in milliseconds. */
    val resumeMs: Long? = null,
    val remainingSec: Int? = null,
    val played: Boolean = false,
    val art: ArtRef? = null,
    /** The podcast's name, as the server sends it with the episode. */
    val podcast: String? = null,
    /** From Audiobookshelf, so it can be downloaded. */
    val downloadable: Boolean = false,
)

/**
 * Everything a podcast page draws: the header from the library index at once, then the newest
 * episodes, more of them as the list nears its end.
 */
@Immutable
data class PodcastUiState(
    val podcast: MediaUi? = null,
    /** Newest first. */
    val episodes: List<EpisodeUi> = emptyList(),
    /** The episode on the current speaker, or starting there. */
    val currentKey: String? = null,
    val loading: Boolean = false,
    /** A further page of episodes is on its way. */
    val loadingMore: Boolean = false,
    /** Every episode the server has is in [episodes]. */
    val complete: Boolean = false,
    val unavailable: Boolean = false,
    /** The last further page failed; the list waits for Retry or the next visit. */
    val moreFailed: Boolean = false,
) {
    val showUnavailable: Boolean = unavailable && (podcast == null || episodes.isEmpty())

    val episodesLoading: Boolean = episodes.isEmpty() && loading

    /** Loaded, and the podcast has no episodes. */
    val isEmpty: Boolean = !loading && !unavailable && episodes.isEmpty()
}
