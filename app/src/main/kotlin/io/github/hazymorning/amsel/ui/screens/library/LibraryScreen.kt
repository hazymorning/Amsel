package io.github.hazymorning.amsel.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.LibrarySort
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.app.Tip
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.components.AmselPullToRefresh
import io.github.hazymorning.amsel.ui.components.EmptyState
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.components.rememberChipRowState
import io.github.hazymorning.amsel.ui.components.rememberSideSwipeState
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.rememberOpenItem
import io.github.hazymorning.amsel.ui.rememberRetry
import kotlinx.coroutines.flow.map

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onSelectKind: (LibraryKind) -> Unit,
    onSort: (LibrarySort) -> Unit,
    onOpen: (MediaUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    onOpenWebUi: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
) {
    val title = stringResource(if (state.single) state.kind.label else PlaybackR.string.library)
    val places = rememberSaveable(saver = LibraryScrollPositions.Saver) { LibraryScrollPositions() }
    val list = places.list(state.kind)
    val grid = places.grid(state.kind)
    val covers = state.kind.covers && !state.offline && state.failure == null
    val titleHeight = remember { mutableIntStateOf(0) }
    // The bar shows once the title has scrolled off, even while the header's chips still show.
    val scrolled by remember(covers, list, grid) {
        derivedStateOf {
            val index = if (covers) grid.firstVisibleItemIndex else list.firstVisibleItemIndex
            val offset = if (covers) grid.firstVisibleItemScrollOffset else list.firstVisibleItemScrollOffset
            index > 0 || (titleHeight.intValue > 0 && offset >= titleHeight.intValue)
        }
    }
    val kinds = state.kinds
    val select: (LibraryKind) -> Unit = { kind ->
        if (kind != state.kind) places.handOver(state.kind, kind)
        onSelectKind(kind)
    }
    val swipe =
        rememberSideSwipeState(state.kind, order = kinds, enabled = (kinds?.size ?: 0) > 1, endless = true) { forward ->
            kinds?.takeIf { it.size > 1 }?.let { it[(it.indexOf(state.kind) + if (forward) 1 else -1).mod(it.size)] }
                ?.let(select)
        }
    val header = LibraryHeader(title, state, select, onSort, rememberChipRowState()) { titleHeight.intValue = it }
    MaterialScreen(
        modifier,
        bar = { PageTopBar(stringResource(state.kind.label), onBack = null, showTitle = scrolled) },
        barShown = scrolled,
    ) { insets ->
        // Inside MaterialScreen, so this screen's top bar learns the list has scrolled under it.
        ScrollEdges(if (covers) grid else list)
        Box(Modifier.fillMaxSize().then(swipe.detect)) {
            when {
                state.offline || state.failure != null -> Column(Modifier.fillMaxSize().padding(insets)) {
                    header.Content(sortable = false)
                    EmptyState(
                        R.drawable.ic_offline,
                        stringResource(state.failure ?: PlaybackR.string.error_not_connected),
                        Modifier.fillMaxWidth().weight(1f),
                        actionLabel = stringResource(R.string.retry),
                        onAction = onRetry,
                    )
                }

                state.pending || state.loading ->
                    LibrarySkeleton(state.kind, header, insets, placeholders = state.loading)

                else -> AmselPullToRefresh(state.refreshing, onRefresh, Modifier.fillMaxSize()) { itemActions ->
                    // Only the items move with a swipe; the header, whose chips show the target, stays.
                    val items = itemActions.then(swipe.follow)
                    when {
                        state.isEmpty ->
                            KindEmptyState(state.kind, onOpenWebUi, header, insets, Modifier.fillMaxSize(), items)

                        state.kind.covers ->
                            CoverGrid(state, grid, header, insets, onOpen, onMore, Modifier.fillMaxSize(), items)

                        else -> RowList(state, list, header, insets, onOpen, onMore, Modifier.fillMaxSize(), items)
                    }
                }
            }
        }
    }
}

/** Kinds recognised by their covers show as a grid of tiles. */
internal val LibraryKind.covers: Boolean
    get() = this == LibraryKind.Albums || this == LibraryKind.Radio || this == LibraryKind.Audiobooks ||
        this == LibraryKind.Podcasts

@Composable
fun LibraryRoute(navigator: Navigator, modifier: Modifier = Modifier, only: LibraryKind? = null) {
    val graph = LocalContext.current.graph
    val actions = LocalAppActions.current
    val viewModel = viewModel {
        val data = graph.data
        LibraryViewModel(
            handle = createSavedStateHandle(),
            observe = data.library::observe,
            syncing = data.library.syncing,
            synced = data.library.synced,
            failures = data.library.failures,
            session = data.session.state,
            user = data.session.user,
            mapper = graph.mapper,
            resync = { type -> data.library.syncType(type, data.session.client.server()) },
            messages = graph.messages,
            playing = graph.playing.playingContext,
            held = data.library.heldTypes,
            arranged = data.prefs.prefs.map { LibraryKind.arrangement(it?.libraryKinds).visible },
            downloads = graph.downloading.rows,
            only = only,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LibraryScreen(
        state = state,
        onSelectKind = viewModel::selectKind,
        onSort = viewModel::sort,
        // Stations and downloaded episodes have no page: a tap plays them.
        onOpen = rememberOpenItem(navigator),
        onMore = {
            graph.tips.seen(Tip.More)
            actions.more(ActionTarget.Media(it))
        },
        onOpenWebUi = actions::openWebUi,
        onRetry = rememberRetry(viewModel::retry),
        onRefresh = viewModel::refresh,
        modifier = modifier,
    )
}
