package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.components.EmptyState
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.components.TrackRow
import io.github.hazymorning.amsel.ui.components.plus
import io.github.hazymorning.amsel.ui.components.rowGlyphBottom
import io.github.hazymorning.amsel.ui.model.TrackUi
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.rememberRetry
import io.github.hazymorning.amsel.ui.theme.Space

/** The songs the server finds similar to the song named [title], as rows that play from the tapped one on. */
@Composable
fun SimilarSongsScreen(
    state: SimilarSongsUiState,
    title: String,
    onBack: () -> Unit,
    onPlayTrack: (TrackUi) -> Unit,
    onMoreTrack: (TrackUi) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    // Where the sentence does not fit the bar, the song's name alone does: a name is cut last.
    val whole = stringResource(R.string.pages_similar_to, title)
    MaterialScreen(modifier, bar = { PageTopBar(whole, onBack, shorterTitle = title) }) { insets ->
        ScrollEdges(list)
        val rows = insets.plus(bottom = Space.ListEnd - rowGlyphBottom(RowLead.Cover, twoLines = true))
        val message = Modifier.fillMaxSize().padding(insets)
        when {
            state.songs.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize(), list, contentPadding = rows) {
                items(state.songs, key = { it.key }, contentType = { PageSlot.Track }) { track ->
                    TrackRow(
                        track,
                        RowLead.Cover,
                        onClick = { onPlayTrack(track) },
                        onMore = { onMoreTrack(track) },
                        playing = track.key == state.currentTrackKey,
                        showAlbum = true,
                    )
                }
            }

            state.loading -> {
                val loading = stringResource(R.string.loading)
                LazyColumn(
                    Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = loading },
                    contentPadding = rows,
                    userScrollEnabled = false,
                ) {
                    trackRowBlocks(RowLead.Cover, withSubtitle = true, count = SKELETON_ROWS)
                }
            }

            state.failure != null -> EmptyState(
                R.drawable.ic_offline,
                stringResource(state.failure),
                message,
                actionLabel = stringResource(R.string.retry),
                onAction = onRetry,
            )

            else -> EmptyState(R.drawable.ic_similar, stringResource(R.string.pages_similar_none), message)
        }
    }
}

/** Under a slim bar alone, a screenful. */
private const val SKELETON_ROWS = 12

/** The songs the server finds similar to the song [key], named [title]. */
@Composable
fun SimilarSongsRoute(navigator: Navigator, key: String, title: String, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val actions = LocalAppActions.current
    val haptics = LocalHapticFeedback.current
    val vm = viewModel { SimilarSongsViewModel(key, graph.pageSource(), graph.mapper, graph.playing.playingUri) }
    val state by vm.state.collectAsStateWithLifecycle()
    SimilarSongsScreen(
        state = state,
        title = title,
        onBack = { navigator.back() },
        onPlayTrack = { track ->
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            graph.playback.play(state.from(track), title = track.title)
        },
        onMoreTrack = { track -> actions.more(ActionTarget.Track(track)) },
        onRetry = rememberRetry(vm::retry),
        modifier = modifier,
    )
}
