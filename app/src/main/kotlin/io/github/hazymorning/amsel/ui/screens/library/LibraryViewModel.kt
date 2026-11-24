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

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val handle: SavedStateHandle,
    private val observe: (MediaType, LibrarySort) -> Flow<List<LibraryItemEntity>>,
    syncing: Flow<Boolean>,
    /** Kinds synced at least once. */
    synced: Flow<Set<MediaType>>,
    failures: Flow<Map<MediaType, Exception>>,
    session: Flow<SessionState>,
    user: Flow<MaUser?>,
    private val mapper: UiMapper,
    /** Throws [MaException] when the sync fails. */
    private val resync: suspend (MediaType) -> Unit,
    private val messages: MessageController,
    work: CoroutineDispatcher = Dispatchers.Default,
    /** Types the index holds at least one item of. */
    held: Flow<Set<MediaType>>,
    arranged: Flow<List<LibraryKind>> = flowOf(LibraryKind.Default.visible),
    playing: Flow<Set<String>> = flowOf(emptySet()),
    /** Downloaded items and those still downloading. */
    private val downloads: Flow<List<MediaUi>> = flowOf(emptyList()),
    private val only: LibraryKind? = null,
) : ViewModel() {
    private val kinds: Flow<List<LibraryKind>> = if (only != null) {
        flowOf(listOf(only))
    } else {
        // Shared: the selection, the state and warm() all collect it, and each collection queries the index.
        combine(arranged, held, downloads.map { it.isNotEmpty() }.distinctUntilChanged(), ::libraryKinds)
            .distinctUntilChanged()
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    }

    private val selection = combine(
        handle.getStateFlow(KIND, only?.name ?: LibraryKind.Playlists.name),
        handle.getStateFlow(SORT, LibrarySort.RecentlyPlayed.name),
        kinds,
    ) { kind, sort, shown ->
        selectionOf(kind, sort).let {
            if (it.kind in shown) it else it.copy(kind = shown.firstOrNull() ?: LibraryKind.Playlists)
        }
    }.distinctUntilChanged()

    private val you: Flow<Set<String>> = user
        .map { u ->
            u?.let { setOfNotNull(it.username, it.displayName).map { n -> n.trim().lowercase() }.toSet() }.orEmpty()
        }
        .distinctUntilChanged()

    /** The last page per selection, shown at once on a switch while the index query runs. */
    private val seen = ConcurrentHashMap<LibrarySelection, LibraryPage>()

    // The selection switches together with its list, so nothing flashes; past PENDING_MS without the
    // index's answer it shows as loading, so the tap still gets an answer in time.
    private val pages: Flow<LibraryPage> = selection.flatMapLatest { selected ->
        // Downloads are not in the index; their order is fixed, newest first.
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

    /** Fills [seen] for the other kinds in [sort], so the first swipe shows them at once. */
    private suspend fun warm(sort: LibrarySort) {
        val me = you.first()
        for (kind in kinds.first()) {
            val type = kind.type ?: continue
            val other = LibrarySelection(kind, sort)
            if (!seen.containsKey(other)) seen[other] = pageOf(other, observe(type, sort).first(), mapper, me)
        }
    }

    private val serverStatus: Flow<ServerStatus> = session.map(::serverStatusOf).distinctUntilChanged()

    /** The kind a pull is syncing; only its list shows the refresh indicator. */
    private val refreshing = MutableStateFlow<LibraryKind?>(null)
    private val syncs = mutableMapOf<LibraryKind, Job>()

    // A pulled sync shows the pull indicator, not the first-load skeleton.
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

    fun refresh() = sync(state.value.kind, pulled = true)

    /** Offline there is no failure to retry: the reconnect that Retry also starts syncs the kind. */
    fun retry() {
        if (state.value.failure != null) sync(state.value.kind, pulled = false)
    }

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

    /** A Retry's failure shows in place, through the failures flow; only a pull's needs a message. */
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

internal data class LibrarySelection(val kind: LibraryKind, val sort: LibrarySort)

/** [items] is null until the index answers. */
internal data class LibraryPage(
    val selection: LibrarySelection,
    val items: List<MediaUi>?,
    val letters: List<LetterJump> = emptyList(),
)

internal fun serverStatusOf(state: SessionState): ServerStatus = when (state) {
    is SessionState.Offline -> ServerStatus.Offline

    is SessionState.Waiting -> ServerStatus.Connecting

    is SessionState.Ready,
    SessionState.Restoring,
    SessionState.NeedsSetup,
    SessionState.Unsupported,
    -> ServerStatus.Connected
}

internal fun selectionOf(kind: String?, sort: String?) = LibrarySelection(
    LibraryKind.entries.firstOrNull { it.name == kind } ?: LibraryKind.Playlists,
    LibrarySort.entries.firstOrNull { it.name == sort } ?: LibrarySort.RecentlyPlayed,
)

/** Drops a playlist's owner when it is the user ([you]: lower-case names), as it would repeat on every row. */
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

/** An empty list before its kind's first finished sync ([synced]) is still loading, not empty. */
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
    val items: List<MediaUi> = emptyList(),
    /** First load only, with nothing cached: skeletons. */
    val loading: Boolean = false,
    /** Before the index's first answer, within PENDING_MS: the header shows alone. */
    val pending: Boolean = false,
    /** Only for an empty list; cached items still show offline. */
    val offline: Boolean = false,
    /** Only for an empty list: why the kind's last sync failed. */
    @StringRes val failure: Int? = null,
    val letters: List<LetterJump> = emptyList(),
    val refreshing: Boolean = false,
    /** Null until the kinds are known. */
    val kinds: List<LibraryKind>? = libraryKinds(LibraryKind.Default.visible, held = emptySet()),
    /** One kind as its own bar tab: the kind is the title, with no chips. */
    val single: Boolean = false,
    /** Keys of what plays and what it comes from (its album, book or podcast). */
    val playing: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = !pending && !loading && !offline && failure == null && items.isEmpty()
}
