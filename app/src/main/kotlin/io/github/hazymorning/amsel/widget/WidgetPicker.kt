package io.github.hazymorning.amsel.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.search
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import io.github.hazymorning.amsel.ui.screens.search.SearchScreen
import io.github.hazymorning.amsel.ui.screens.search.SearchViewModel
import kotlinx.coroutines.flow.map

/** Search, for what a Play … button plays: whatever is tapped, or held, is the pick. */
@Composable
fun WidgetPicker(onPick: (WidgetItem) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val viewModel = viewModel(key = PICK) {
        SearchViewModel(
            handle = createSavedStateHandle(),
            search = { graph.data.session.api.search(it) },
            mapper = graph.mapper,
            playing = graph.playing.playingUri,
            library = graph.data.library.everything.map { items -> items.map(graph.mapper::media) },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recent by graph.recentSearches.items.collectAsStateWithLifecycle()
    val media = { item: MediaUi -> onPick(WidgetItem(item.key, item.title, item.art?.face())) }
    val track = { item: TrackUi -> onPick(WidgetItem(item.key, item.title, item.art?.face())) }
    SearchScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onPlayTrack = track,
        onOpen = media,
        onTrackMore = track,
        onMore = media,
        onRetry = viewModel::retry,
        modifier = modifier,
        recent = recent,
        onClearRecent = graph.recentSearches::clear,
        bar = { PageTopBar(stringResource(R.string.widget_pick_title), onBack) },
    )
}

/** The cover a button wears: an artist without a picture wears the first of its albums'. */
private fun ArtRef.face(): String = (if (mosaic) quarters.firstOrNull() ?: this else this).sized(FACE_PX)

/** As large as a tile's cover, about 80 dp; a button's face of 28 is made from it too. */
private const val FACE_PX = 256
private const val PICK = "widget-pick"
