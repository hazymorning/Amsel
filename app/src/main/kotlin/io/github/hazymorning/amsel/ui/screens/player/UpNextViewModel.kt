package io.github.hazymorning.amsel.ui.screens.player

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.PlayerQueue
import io.github.hazymorning.amsel.api.QueueItem
import io.github.hazymorning.amsel.app.Messages
import io.github.hazymorning.amsel.app.UpNextEdit
import io.github.hazymorning.amsel.app.retryWhileOpen
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.data.Speakers
import io.github.hazymorning.amsel.playback.RemotePlayer
import io.github.hazymorning.amsel.playback.failureText
import io.github.hazymorning.amsel.ui.components.moved
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.QueueItemUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Up next for one speaker, loaded while shown: a page around the song that plays, then more as the
 * list nears either end. What is shown loads again when the server says its items changed or the
 * connection comes back (events are not replayed). Taps, moves and removals show at once and run
 * in [appScope], so closing Up next does not cancel them.
 */
class UpNextViewModel(
    private val queueId: String,
    private val api: MaApi,
    private val speakers: Speakers,
    session: Flow<SessionState>,
    private val mapper: UiMapper,
    private val messages: Messages,
    private val appScope: CoroutineScope,
    /** Runs before a tapped song starts: on This phone it releases the hold a pause left. */
    private val beforePlay: (queueId: String) -> Unit,
    /** The ⋮ sheet's changes to these rows. */
    sheetEdits: Flow<UpNextEdit>,
    /** Queues a song again to play next, for one the server will not move. */
    private val queueNext: (uri: String) -> Unit,
) : ViewModel() {
    private val shown = MutableStateFlow(UpNextUiState(loading = true))
    private val sending = Mutex()

    /** The row tapped to play: current here until the server reports it, or until the speaker's confirm time runs out. */
    private val tapped = MutableStateFlow<String?>(null)
    private var tapHold: Job? = null
    private var paging: Job? = null

    /** Changes made here; a load that saw one made meanwhile is stale, and the next one applies. */
    private var changes = 0
    private val reloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Reads that end with this screen: their Retry is taken back with it.
    private val retryEarlier = retryWhileOpen(messages) { more(earlier = true) }
    private val retryLater = retryWhileOpen(messages) { more(earlier = false) }
    private val retryReload = retryWhileOpen(messages, ::reload)
    private val reconnects = session.map { it is SessionState.Ready }.distinctUntilChanged().drop(1).filter { it }

    val state: StateFlow<UpNextUiState> = channelFlow {
        launch {
            merge(reloads, reconnects.map {}, speakers.queueItemsChanged.filter { it == queueId }.map {})
                .onStart { emit(Unit) }
                .collectLatest { load() }
        }
        launch {
            combine(
                speakers.queues.map {
                    it[queueId]?.queue
                }.distinctUntilChanged(),
                tapped,
                ::Pair,
            ).collect { (queue, tap) ->
                if (tap != null && queue?.currentItem?.queueItemId == tap) tapped.value = null
                shown.update { it.following(queue, tapped.value) }
            }
        }
        launch {
            sheetEdits.collect { edit ->
                when (edit) {
                    is UpNextEdit.Remove -> remove(edit.queueItemId)
                    is UpNextEdit.PlayNext -> playNext(edit.queueItemId, edit.uri)
                }
            }
        }
        shown.collect { send(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), shown.value)

    fun reload() {
        // Retry in place of the list shows it loading at once: the read may wait for the connection.
        shown.update { if (it.failure != null) it.copy(loading = true, failure = null) else it }
        reloads.tryEmit(Unit)
    }

    /** The songs before the first one shown, as the list nears its top. */
    fun loadEarlier() = more(earlier = true)

    /** The songs after the last one shown, as the list nears its end. */
    fun loadLater() = more(earlier = false)

    private fun more(earlier: Boolean) {
        val now = shown.value
        if (now.loading || paging?.isActive == true || (if (earlier) !now.hasEarlier else !now.hasLater)) return
        val from = if (earlier) (now.start - MaApi.PAGE).coerceAtLeast(0) else now.start + now.items.size
        val retry = if (earlier) retryEarlier else retryLater
        paging = viewModelScope.launch {
            sending.withLock {
                val page = try {
                    api.queueItems(queueId, from, if (earlier) now.start - from else MaApi.PAGE)
                } catch (e: MaConnectionException) {
                    return@withLock messages.show(failureText(e), retry = retry)
                } catch (e: MaCommandException) {
                    return@withLock messages.show(failureText(e), retry = retry)
                }
                // A reload meanwhile shows another stretch, which this page does not join.
                if (shown.value.start != now.start) return@withLock
                val queue = speakers.queues.value[queueId]?.queue
                val rows = page.items.map { mapper.queueItem(it, queue?.currentItem?.queueItemId) }
                shown.update {
                    val ids = it.items.mapTo(HashSet()) { row -> row.id }
                    val fresh = rows.filterNot { row -> row.id in ids }
                    val items = if (earlier) fresh + it.items else it.items + fresh
                    it.copy(items = items, start = if (earlier) from else it.start).following(queue, tapped.value)
                }
            }
        }
    }

    fun play(itemId: String) {
        tapped.value = itemId
        tapHold?.cancel()
        tapHold = viewModelScope.launch {
            delay(RemotePlayer.CONFIRM_TIMEOUT_MS)
            tapped.compareAndSet(itemId, null)
        }
        beforePlay(queueId)
        send(undo = { it }, retry = { play(itemId) }) { api.playIndex(queueId, itemId) }
    }

    fun move(itemId: String, toIndex: Int) {
        val state = shown.value
        val from = state.items.indexOfFirst { it.id == itemId }
        val to = toIndex.coerceAtLeast(state.firstEditable)
        if (from < state.firstEditable || from == to) return
        changes++
        shown.value = state.copy(items = state.items.moved(from, to))
        send(undo = { it.withRowAt(itemId, from) }, retry = { move(itemId, toIndex) }) {
            api.moveItem(queueId, itemId, to - from)
        }
    }

    fun remove(itemId: String) {
        val state = shown.value
        val at = state.items.indexOfFirst { it.id == itemId }
        if (at < state.firstEditable) return
        val row = state.items[at]
        changes++
        shown.value = state.copy(items = state.items.filterNot { it.id == itemId })
        send(undo = { it.withRow(row, at) }, retry = { remove(itemId) }) { api.deleteItem(queueId, itemId) }
    }

    /** The ⋮ sheet's Play next: the row moves up to play next. */
    private fun playNext(itemId: String, uri: String) {
        val state = shown.value
        val from = state.items.indexOfFirst { it.id == itemId }
        if (from < state.firstEditable) return
        changes++
        shown.value = state.copy(items = state.items.moved(from, state.firstEditable))
        send(undo = { it.withRowAt(itemId, from) }, retry = { playNext(itemId, uri) }) {
            try {
                api.moveItem(queueId, itemId, shift = 0)
            } catch (e: MaCommandException) {
                queueNext(uri)
            }
        }
    }

    /**
     * A change the screen already shows. A failure takes it back ([undo]) and says so; one the
     * server refused reloads its list, and a lost connection reloads once it is back. Changes reach
     * the server in the order they were made: each queues on the fair [sending] lock before the
     * call returns, as a move or removal counts on the ones before it.
     */
    private fun send(undo: (UpNextUiState) -> UpNextUiState, retry: () -> Unit, change: suspend () -> Unit) {
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sending.withLock {
                val error = try {
                    change()
                    return@withLock
                } catch (e: MaConnectionException) {
                    e
                } catch (e: MaCommandException) {
                    reload()
                    e
                }
                tapped.value = null
                shown.update(undo)
                messages.show(failureText(error), retry = retry)
            }
        }
    }

    /**
     * After the changes on their way, so the list it reads holds them. The first load opens a page
     * around the song that plays; a reload reads again the stretch that is shown.
     */
    private suspend fun load() = sending.withLock {
        val before = changes
        val now = shown.value
        val anchor = speakers.queues.value[queueId]?.queue?.currentIndex ?: 0
        val from = if (now.items.isEmpty()) (anchor - PLAYED_ABOVE).coerceAtLeast(0) else now.start
        try {
            val items = stretch(from, count = now.items.size)
            if (changes != before) return@withLock
            val queue = speakers.queues.value[queueId]?.queue
            val current = queue?.currentItem?.queueItemId
            shown.value = UpNextUiState(items.map { mapper.queueItem(it, current) }, start = from)
                .following(queue, tapped.value)
        } catch (e: MaConnectionException) {
            loadFailed(e)
        } catch (e: MaCommandException) {
            loadFailed(e)
        }
    }

    /** At least [count] items from [from] (one page at the least), a page at a time, or all there are. */
    private suspend fun stretch(from: Int, count: Int): List<QueueItem> {
        val items = mutableListOf<QueueItem>()
        var at = from
        do {
            val page = api.queueItems(queueId, at)
            items += page.items
            at = page.next
        } while (!page.complete && at - from < count)
        return items
    }

    /** Said in place of the list while it is empty, else in a message. */
    private fun loadFailed(error: Exception) {
        if (shown.value.items.isEmpty()) {
            shown.update { it.copy(loading = false, failure = failureText(error)) }
        } else {
            messages.show(failureText(error), retry = retryReload)
        }
    }
}

/**
 * Up next for the current speaker: songs already played, the current one (marked), then what
 * comes next, in play order; [items] is the stretch loaded so far, from the queue's [start].
 */
@Immutable
data class UpNextUiState(
    val items: List<QueueItemUi> = emptyList(),
    /** Where [items] begins in the queue. */
    val start: Int = 0,
    /** How many items the queue holds, loaded or not. */
    val total: Int = 0,
    /** The very first load, with nothing to show yet: skeleton rows. */
    val loading: Boolean = false,
    /** Why nothing could be loaded, said in place of the list. */
    @StringRes val failure: Int? = null,
    /** The first row that may move or go: the server keeps what the speaker already holds. */
    val firstEditable: Int = 0,
) {
    val isEmpty: Boolean get() = !loading && failure == null && items.isEmpty()

    val hasEarlier: Boolean get() = start > 0

    val hasLater: Boolean get() = start + items.size < total

    /** Where the list opens: the songs already played wait above it, under the bar. */
    val currentIndex: Int get() = items.indexOfFirst { it.isCurrent }.coerceAtLeast(0)
}

/**
 * [state] as the server has [queue]: its current item marked ([tapped] while the server has not
 * reported it yet), and nothing at or before what the speaker already holds (the current item, and
 * the next once buffered) editable.
 */
internal fun UpNextUiState.following(queue: PlayerQueue?, tapped: String? = null): UpNextUiState {
    val current = tapped ?: queue?.currentItem?.queueItemId
    val held = listOfNotNull(queue?.currentIndex, queue?.indexInBuffer).maxOrNull() ?: -1
    return copy(
        items = items.map { it.copy(isCurrent = it.id == current) },
        firstEditable = (held + 1 - start).coerceAtLeast(0),
        total = queue?.items ?: (start + items.size),
    )
}

/** How many played songs the first load shows above the one that plays; more come with a pull. */
private const val PLAYED_ABOVE = 50

/** [row] back at [index], unless it is there. */
private fun UpNextUiState.withRow(row: QueueItemUi, index: Int): UpNextUiState = if (items.any {
        it.id == row.id
    }
) {
    this
} else {
    copy(items = items.toMutableList().apply { add(index.coerceAtMost(size), row) })
}

/** Row [id] back at [index], wherever it is now. */
private fun UpNextUiState.withRowAt(id: String, index: Int): UpNextUiState {
    val at = items.indexOfFirst { it.id == id }
    return if (at < 0) this else copy(items = items.moved(at, index.coerceAtMost(items.lastIndex)))
}
