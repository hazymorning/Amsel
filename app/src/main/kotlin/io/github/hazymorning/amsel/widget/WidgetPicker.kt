package io.github.hazymorning.amsel.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.search
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.components.ChoiceChipRow
import io.github.hazymorning.amsel.ui.components.EmptyState
import io.github.hazymorning.amsel.ui.components.MediaRow
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.components.withTop
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import io.github.hazymorning.amsel.ui.screens.library.LibraryKind
import io.github.hazymorning.amsel.ui.screens.library.LibraryUiState
import io.github.hazymorning.amsel.ui.screens.library.LibraryViewModel
import io.github.hazymorning.amsel.ui.screens.library.label
import io.github.hazymorning.amsel.ui.screens.search.SearchScreen
import io.github.hazymorning.amsel.ui.screens.search.SearchViewModel
import io.github.hazymorning.amsel.ui.theme.Space
import kotlinx.coroutines.flow.map

/**
 * What a Play … button plays: with the field empty, the library by kind, played lately first, as
 * the Library tab has it; or what Search finds. Whatever is tapped, or held, is the pick.
 */
@Composable
fun WidgetPicker(onPick: (WidgetItem) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val search = viewModel(key = PICK) {
        SearchViewModel(
            handle = createSavedStateHandle(),
            search = { graph.data.session.api.search(it) },
            mapper = graph.mapper,
            playing = graph.playing.playingUri,
            library = graph.data.library.everything.map { items -> items.map(graph.mapper::media) },
        )
    }
    val library = viewModel(key = SHELF) {
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
            held = data.library.heldTypes,
            arranged = data.prefs.prefs.map { LibraryKind.arrangement(it?.libraryKinds).visible },
        )
    }
    val state by search.state.collectAsStateWithLifecycle()
    val shelf by library.state.collectAsStateWithLifecycle()
    val media = { item: MediaUi -> onPick(WidgetItem(item.key, item.title, item.art?.face())) }
    val track = { item: TrackUi -> onPick(WidgetItem(item.key, item.title, item.art?.face())) }
    SearchScreen(
        state = state,
        onQueryChange = search::onQueryChange,
        onPlayTrack = track,
        onOpen = media,
        onTrackMore = track,
        onMore = media,
        onRetry = search::retry,
        modifier = modifier,
        bar = { PageTopBar(stringResource(R.string.widget_pick_title), onBack) },
        browse = { insets -> WidgetShelf(shelf, library::selectKind, library::retry, media, insets) },
    )
}

/** The library's kinds as chips, the chosen one's items under them, each a pick. */
@Composable
internal fun WidgetShelf(
    state: LibraryUiState,
    onKind: (LibraryKind) -> Unit,
    onRetry: () -> Unit,
    onPick: (MediaUi) -> Unit,
    insets: PaddingValues,
) {
    val list = rememberLazyListState()
    ScrollEdges(list)
    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
        contentPadding = insets.withTop(Space.HeaderToRows, bottom = Space.ListEnd),
    ) {
        state.kinds?.filter { it != LibraryKind.Downloads }?.takeIf { it.size > 1 }?.let { kinds ->
            item(key = KINDS, contentType = KINDS) {
                // Under the chips as far as the field stands over them.
                Column(Modifier.padding(bottom = Space.Part)) {
                    ChoiceChipRow(kinds, state.kind, onKind, label = { stringResource(it.label) }, role = Role.Tab)
                }
            }
        }
        if (state.offline || state.failure != null) {
            item(key = FAILURE, contentType = FAILURE) {
                EmptyState(
                    R.drawable.ic_offline,
                    stringResource(state.failure ?: PlaybackR.string.error_not_connected),
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRetry,
                )
            }
        } else {
            items(state.items, key = { it.key }, contentType = { ROW }) { item ->
                MediaRow(item, onClick = { onPick(item) }, onMore = { onPick(item) })
            }
        }
    }
}

/** The cover a button wears: an artist without a picture wears the first of its albums'. */
private fun ArtRef.face(): String = (if (mosaic) quarters.firstOrNull() ?: this else this).sized(FACE_PX)

/** As large as a tile's cover, about 80 dp; a button's face of 32 is made from it too. */
private const val FACE_PX = 256
private const val PICK = "widget-pick"
private const val SHELF = "widget-shelf"
private const val KINDS = "kinds"
private const val FAILURE = "failure"
private const val ROW = "row"
