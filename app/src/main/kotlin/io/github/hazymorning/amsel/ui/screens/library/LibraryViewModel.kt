package io.github.hazymorning.amsel.ui.screens.library

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.LibrarySort
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.MaUser
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.app.MessageController
import io.github.hazymorning.amsel.app.retryWhileOpen
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.data.library.LibraryItemEntity
import io.github.hazymorning.amsel.playback.failureText
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.ServerStatus
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Library tab: the chosen kind in the chosen order, straight from the library index, so it
 * paints instantly from the cache. Kind and order survive process death in [handle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val handle: SavedStateHandle,
    private val observe: (MediaType, LibrarySort) -> Flow<List<LibraryItemEntity>>,
    syncing: Flow<Boolean>,
    /** The kinds the index has synced at least once (LibraryRepository.synced). */
    synced: Flow<Set<MediaType>>,
    /** Why a kind's last sync failed (LibraryRepository.failures). */
    failures: Flow<Map<MediaType, Exception>>,
    session: Flow<SessionState>,
    /** Who is signed in, kept by the session while offline. */
    user: Flow<MaUser?>,
    private val mapper: UiMapper,
    /** Syncs one kind of the library index with the server again; throws when that fails. */
    private val resync: suspend (MediaType) -> Unit,
    private val messages: MessageController,
    work: CoroutineDispatcher = Dispatchers.Default,
    /** The kinds the index holds any of: the Audiobooks, Podcasts and Radio chips show only then. */
    held: Flow<Set<MediaType>>,
    /** The kinds the person shows in the Library, in their order (Settings, Library). */
    arranged: Flow<List<LibraryKind>> = flowOf(LibraryKind.Default.visible),
    /** What plays now and what it comes from (the track, its album, the book or podcast). */
    playing: Flow<Set<String>> = flowOf(emptySet()),
    /** What is downloaded to the phone, or on its way, as rows: the Downloads chip shows while there is some. */
    private val downloads: Flow<List<MediaUi>> = flowOf(emptyList()),
    /** One kind alone, as a place of its own in the bar: its name for a title, and no chips. */
    private val only: LibraryKind? = null,
) : ViewModel() {
    private val kinds: Flow<List<LibraryKind>> = if (only != null) {
        flowOf(listOf(only))
    } else {
        // Read once for the selection, the state and warm(), not once for each: it asks the index.
        combine(arranged, held, downloads.map { it.isNotEmpty() }.distinctUntilChanged(), ::libraryKinds)
            .distinctUntilChanged()
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    }

    // A chosen kind the library no longer holds, or the person no longer shows, gives way to the first chip.
    private val selection = combine(
        handle.getStateFlow(KIND, only?.name ?: LibraryKind.Playlists.name),
        handle.getStateFlow(SORT, LibrarySort.RecentlyPlayed.name),
        kinds,
    ) { kind, sort, shown ->
        selectionOf(kind, sort).let {
            if (it.kind in shown) it else it.copy(kind = shown.firstOrNull() ?: LibraryKind.Playlists)
        }
    }.distinctUntilChanged()

    /** The signed-in person's names. */
    private val you: Flow<Set<String>> = user
        .map { u ->
            u?.let { setOfNotNull(it.username, it.displayName).map { n -> n.trim().lowercase() }.toSet() }.orEmpty()
        }
        .distinctUntilChanged()

    /** Each kind's last list, so a swipe or a chip shows it at once while the index answers. */
    private val seen = ConcurrentHashMap<LibrarySelection, LibraryPage>()

    // A new chip or order switches together with its list, so nothing flashes; if the index takes
    // longer than PENDING_MS, the new selection shows as loading so the tap is answered anyway.
    private val pages: Flow<LibraryPage> = selection.flatMapLatest { selected ->
        // Downloads are on the phone, not in the index: newest first, whatever the order.
        val type = selected.kind.type ?: return@flatMapLatest downloads.map { LibraryPage(selected, it) }
        channelFlow {
            val cached = seen[selected]?.also { send(it) }
            val slow = if (cached != null) {
                null
            } else {
                launch {
                    delay(PENDING_MS)
                    send(LibraryPage(selected, items = null))
                }
            }
            val entities = observe(type, selected.sort).distinctUntilChanged()
            var warmed = false
            combine(entities, you) { list, me -> pageOf(selected, list, mapper, me) }
                .flowOn(work)
                .collect {
                    slow?.cancel()
                    seen[selected] = it
                    send(it)
                    if (!warmed) launch(work) { warm(selected.sort) }
                    warmed = true
                }
        }
    }

    /** The other kinds in [sort], read once beside the chosen one, so the first swipe finds them ready. */
    private suspend fun warm(sort: LibrarySort) {
        val me = you.first()
        for (kind in kinds.first()) {
            val type = kind.type ?: continue
            val other = LibrarySelection(kind, sort)
            if (!seen.containsKey(other)) seen[other] = pageOf(other, observe(type, sort).first(), mapper, me)
        }
    }

    private val serverStatus: Flow<ServerStatus> = session.map(::serverStatusOf).distinctUntilChanged()

    /** The kind a pull is syncing: its chip spins, the others do not. */
    private val refreshing = MutableStateFlow<LibraryKind?>(null)
    private val syncs = mutableMapOf<LibraryKind, Job>()

    // A pull's own spinner says the list is on its way; the sync it starts is not a first load.
    val state: StateFlow<LibraryUiState> =
        combine(
            combine(pages, combine(syncing, synced, ::Pair), serverStatus, refreshing, kinds) {
                    page,
                    (sync, done),
                    status,
                    pulledKind,
                    shown,
                ->
                val pulled = pulledKind == page.selection.kind
                val synced = page.selection.kind.type?.let { it in done } ?: true
                libraryState(page, sync && !pulled, status, synced)
                    .copy(refreshing = pulled, kinds = shown, single = only != null)
            },
            failures,
            playing,
        ) { state, failed, now ->
            val failure = state.kind.type?.let { failed[it] }
                ?.takeIf { state.items.isEmpty() && !state.loading && !state.offline }
            state.copy(playing = now, failure = failure?.let(::failureText))
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            selectionOf(only?.name ?: handle[KIND], handle[SORT]).let {
                LibraryUiState(kind = it.kind, sort = it.sort, pending = true, kinds = null, single = only != null)
            },
        )

    fun selectKind(kind: LibraryKind) {
        handle[KIND] = kind.name
    }

    fun sort(sort: LibrarySort) {
        handle[SORT] = sort.name
    }

    /**
     * Pull to refresh: the chip's kind syncs with the server again, and its list follows the index
     * as it changes. A pull while that kind syncs joins it.
     */
    fun refresh() = sync(state.value.kind, pulled = true)

    /** Retry: the kind on screen syncs again when its sync failed; offline, reconnecting syncs it. */
    fun retry() {
        if (state.value.failure != null) sync(state.value.kind, pulled = false)
    }

    /** [kind] is the one on screen: a chosen kind with nothing left in it shows another one. */
    private fun sync(kind: LibraryKind, pulled: Boolean) {
        val type = kind.type ?: return
        if (syncs[kind]?.isActive == true) return
        if (pulled) refreshing.value = kind
        syncs[kind] = viewModelScope.launch {
            try {
                resync(type)
            } catch (e: MaException) {
                failed(kind, pulled)
            } finally {
                refreshing.compareAndSet(kind, null)
            }
        }
    }

    /** A pull's failure is said in a message; a Retry's shows in place again, as the kind's failure. */
    private fun failed(kind: LibraryKind, pulled: Boolean) {
        if (pulled) {
            messages.show(
                R.string.library_refresh_failed,
                retry = retryWhileOpen(messages) {
                    sync(kind, pulled = true)
                },
            )
        }
    }

    private companion object {
        const val KIND = "kind"
        const val SORT = "sort"
        const val PENDING_MS = 100L
    }
}

/** What the chips and the sort button have selected. */
internal data class LibrarySelection(val kind: LibraryKind, val sort: LibrarySort)

/** One kind's list in one order; [items] is null while the index has not answered yet. */
internal data class LibraryPage(
    val selection: LibrarySelection,
    val items: List<MediaUi>?,
    val letters: List<LetterJump> = emptyList(),
)

/** What the screen believes about the server while a list is empty. */
internal fun serverStatusOf(state: SessionState): ServerStatus = when (state) {
    is SessionState.Offline -> ServerStatus.Offline
    is SessionState.Waiting -> ServerStatus.Connecting
    else -> ServerStatus.Connected
}

internal fun selectionOf(kind: String?, sort: String?) = LibrarySelection(
    LibraryKind.entries.firstOrNull { it.name == kind } ?: LibraryKind.Playlists,
    LibrarySort.entries.firstOrNull { it.name == sort } ?: LibrarySort.RecentlyPlayed,
)

/**
 * A playlist names its owner only when that is someone else: the person's own name on every row
 * says nothing. [you] holds the signed-in person's names, in lower case.
 */
internal fun pageOf(
    selection: LibrarySelection,
    entities: List<LibraryItemEntity>,
    mapper: UiMapper,
    you: Set<String> = emptySet(),
) = LibraryPage(
    selection = selection,
    items = entities.map(mapper::media).map {
        val mine = selection.kind == LibraryKind.Playlists && it.subtitle?.trim()?.lowercase() in you
        if (mine) it.copy(subtitle = null) else it
    },
    letters = if (selection.sort == LibrarySort.AToZ) letterJumps(entities.map { it.sortName }) else emptyList(),
)

/**
 * Skeletons while the list is unknown, or empty on its kind's first sync ([synced] once one has
 * finished); "Can't reach" only for an empty list.
 */
internal fun libraryState(page: LibraryPage, syncing: Boolean, status: ServerStatus, synced: Boolean): LibraryUiState {
    val items = page.items
    val empty = items != null && items.isEmpty()
    return LibraryUiState(
        kind = page.selection.kind,
        sort = page.selection.sort,
        items = items.orEmpty(),
        loading =
            items == null ||
                (empty && !synced && status != ServerStatus.Offline && (syncing || status == ServerStatus.Connecting)),
        offline = empty && status == ServerStatus.Offline,
        letters = page.letters,
    )
}

@Immutable
data class LibraryUiState(
    val kind: LibraryKind = LibraryKind.Playlists,
    val sort: LibrarySort = LibrarySort.RecentlyPlayed,
    /** The selected kind's items, already in [sort] order. */
    val items: List<MediaUi> = emptyList(),
    /** The very first load, with nothing cached yet: skeletons. Afterwards the cache paints instantly. */
    val loading: Boolean = false,
    /** The index has not answered yet, but only just: the header shows alone until it is slow. */
    val pending: Boolean = false,
    /** Nothing cached and Music Assistant cannot be reached. */
    val offline: Boolean = false,
    /** Nothing cached and the kind's last sync failed: why, with Retry. */
    @StringRes val failure: Int? = null,
    /** The scrubber's letters (see [letterJumps]); empty hides the scrubber. */
    val letters: List<LetterJump> = emptyList(),
    /** A pull to refresh is syncing the list with the server. */
    val refreshing: Boolean = false,
    /** The chips, in order; null until the library says what it holds. */
    val kinds: List<LibraryKind>? = libraryKinds(LibraryKind.Default.visible, held = emptySet()),
    /** One kind alone, a place of its own in the bar: named in the title, with no chips. */
    val single: Boolean = false,
    /** What plays now and what it comes from; such an item carries the playing ring. */
    val playing: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = !pending && !loading && !offline && failure == null && items.isEmpty()
}
