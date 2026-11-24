package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.max
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.QueueOption
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.PlayerLayerState
import io.github.hazymorning.amsel.ui.components.EmptyState
import io.github.hazymorning.amsel.ui.components.LoadAhead
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.components.fadingEdges
import io.github.hazymorning.amsel.ui.components.moved
import io.github.hazymorning.amsel.ui.components.rememberReorderState
import io.github.hazymorning.amsel.ui.components.rowBand
import io.github.hazymorning.amsel.ui.components.rowGrowth
import io.github.hazymorning.amsel.ui.layerDrag
import io.github.hazymorning.amsel.ui.model.ActionsContext
import io.github.hazymorning.amsel.ui.model.QueueItemUi
import io.github.hazymorning.amsel.ui.rememberRetry
import io.github.hazymorning.amsel.ui.theme.Space

/**
 * The list opens on the song that plays, right under the bar; the songs already played wait above
 * it, under the bar, and come into view with a pull.
 */
@Composable
fun UpNextScreen(
    state: UpNextUiState,
    onClose: () -> Unit,
    onPlay: (itemId: String) -> Unit,
    onMove: (itemId: String, toIndex: Int) -> Unit,
    onRemove: (itemId: String) -> Unit,
    onMore: (QueueItemUi) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    listScroll: NestedScrollConnection? = null,
    onLoadEarlier: () -> Unit = {},
    onLoadLater: () -> Unit = {},
) {
    MaterialScreen(
        modifier.background(MaterialTheme.colorScheme.surface),
        bar = {
            PageTopBar(
                stringResource(R.string.player_up_next),
                onBack = onClose,
                backIcon = R.drawable.ic_collapse,
                backLabel = stringResource(R.string.close),
            )
        },
    ) { insets ->
        when {
            state.loading -> UpNextSkeleton(Modifier.padding(top = insets.calculateTopPadding()))

            state.failure != null -> EmptyState(
                R.drawable.ic_offline,
                stringResource(state.failure),
                Modifier.fillMaxSize().padding(insets),
                actionLabel = stringResource(R.string.retry),
                onAction = onRetry,
            )

            state.isEmpty -> EmptyState(
                R.drawable.ic_queue,
                stringResource(R.string.player_up_next_empty),
                Modifier.fillMaxSize().padding(insets),
            )

            else -> QueueList(state, insets, onPlay, onMove, onRemove, onMore, listScroll, onLoadEarlier, onLoadLater)
        }
    }
}

/** The current song's cover stays clear of the bar's edge. */
private val BelowBar = Space.S2

@Composable
private fun QueueList(
    state: UpNextUiState,
    insets: PaddingValues,
    onPlay: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    onMore: (QueueItemUi) -> Unit,
    listScroll: NestedScrollConnection?,
    onLoadEarlier: () -> Unit,
    onLoadLater: () -> Unit,
) {
    // It moves under the finger and settles when the next state arrives.
    var order by remember(state.items) { mutableStateOf(state.items) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = state.currentIndex)
    ScrollEdges(list)
    LoadAhead(list, onNearEnd = onLoadLater, onNearStart = onLoadEarlier)
    fun indexOf(key: Any) = order.indexOfFirst { it.id == key }
    val reorder = rememberReorderState(
        list,
        movable = { indexOf(it) >= state.firstEditable },
        onSwap = { from, to -> order = order.moved(indexOf(from), indexOf(to)) },
        onDrop = { key -> onMove(key as String, indexOf(key)) },
    )
    val remove = { item: QueueItemUi ->
        order = order.filterNot { it.id == item.id }
        onRemove(item.id)
    }
    val move = { from: Int, to: Int ->
        val id = order[from].id
        order = order.moved(from, to)
        onMove(id, to)
    }
    val playingIndex = order.indexOfFirst { it.isCurrent }
    val top = insets.calculateTopPadding() + BelowBar
    val bars = insets.calculateBottomPadding() + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val row = rowBand(RowLead.Cover, twoLines = true) + rowGrowth(RowLead.Cover, twoLines = true)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A short Up next still opens on the song that plays: room under the last song lets it reach the top.
        val ahead = order.size - playingIndex.coerceAtLeast(0)
        val bottom = max(bars, maxHeight - top - row * ahead)
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(top = top, bottom = bottom),
            modifier = Modifier
                .fillMaxSize()
                .then(if (listScroll != null) Modifier.nestedScroll(listScroll) else Modifier)
                .fadingEdges(list),
        ) {
            itemsIndexed(order, key = { _, item -> item.id }, contentType = { _, _ -> "queue-item" }) { index, item ->
                val dragging = reorder.draggingKey == item.id
                val settling = reorder.settlingKey == item.id
                val placement = if (dragging || settling) {
                    Modifier.zIndex(1f).graphicsLayer { translationY = reorder.offsetOf(item.id) }
                } else {
                    Modifier.animateItem()
                }
                // Not saved: the list keeps a gone row's saved state, so a row a failed removal brings
                // back would return dismissed and remove itself again.
                val threshold = SwipeToDismissBoxDefaults.positionalThreshold
                val dismiss = remember(item.id) { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
                SwipeToDismissBox(
                    state = dismiss,
                    modifier = placement,
                    gesturesEnabled = index >= state.firstEditable && !dragging,
                    onDismiss = { remove(item) },
                    backgroundContent = { RemoveBackground(dismiss.dismissDirection) },
                ) {
                    val played = index < playingIndex
                    QueueRow(
                        item = item,
                        played = played,
                        lifted = dragging || settling,
                        reorder = if (index < state.firstEditable) null else reorder,
                        actions = rowActions(index, order.lastIndex, state.firstEditable, item, move, remove),
                        onClick = { onPlay(item.id) },
                        // The DJ's words can only be removed, and no longer once the speaker holds them.
                        onMore = { onMore(item) }.takeIf { !item.track.djBreak || index >= state.firstEditable },
                    )
                }
            }
        }
    }
}

/**
 * Its bar and empty states drag the layer; its list hands a pull past its top to the layer. Its
 * ViewModel ends with the layer, so it opens again around the song that plays now.
 */
@Composable
fun UpNextRoute(queueId: String, onClose: () -> Unit, layer: PlayerLayerState, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val actions = LocalAppActions.current
    val vm =
        viewModel(rememberViewModelStoreOwner(), key = "upnext-$queueId") {
            UpNextViewModel(
                queueId,
                graph.data.session.api,
                graph.data.speakers,
                graph.data.session.state,
                graph.mapper,
                graph.messages,
                graph.scope,
                beforePlay = graph.playback::beforePlayOn,
                sheetEdits = graph.actions.upNextEdits,
                queueNext = { uri -> graph.playback.play(listOf(uri), option = QueueOption.Next) },
            )
        }
    val state by vm.state.collectAsStateWithLifecycle()
    UpNextScreen(
        state = state,
        onClose = onClose,
        onPlay = vm::play,
        onMove = vm::move,
        onRemove = vm::remove,
        onMore = { item ->
            // A song the speaker already holds stays where it is: offered again rather than moved or removed.
            val editable = state.items.indexOf(item) >= state.firstEditable
            val context = if (editable) ActionsContext.UpNext else ActionsContext.Library
            actions.more(ActionTarget.Track(item.track, context, queueItemId = item.id.takeIf { editable }))
        },
        onRetry = rememberRetry(vm::reload),
        modifier = modifier.layerDrag(layer),
        listScroll = layer.nestedScroll,
        onLoadEarlier = vm::loadEarlier,
        onLoadLater = vm::loadLater,
    )
}
