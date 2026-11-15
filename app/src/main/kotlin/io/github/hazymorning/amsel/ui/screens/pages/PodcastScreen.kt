package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.data.downloads.DownloadState
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.components.EmptyState
import io.github.hazymorning.amsel.ui.components.LoadAhead
import io.github.hazymorning.amsel.ui.components.MoreButton
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.TextAction
import io.github.hazymorning.amsel.ui.components.inkStart
import io.github.hazymorning.amsel.ui.components.rowInkBottom
import io.github.hazymorning.amsel.ui.components.rowInkTop
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.rememberRetry
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space

/**
 * A podcast: its cover and title as an album's, then its episodes, newest first. More come as the
 * list nears its end.
 */
@Composable
fun PodcastScreen(
    state: PodcastUiState,
    onBack: () -> Unit,
    onPlayEpisode: (EpisodeUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    onMoreEpisode: (EpisodeUi) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryMore: () -> Unit = {},
    /** Episodes on the phone, or on their way there, by uri. */
    downloads: Map<String, DownloadState> = emptyMap(),
) {
    if (state.showUnavailable) {
        PageUnavailable(
            R.drawable.ic_podcast,
            stringResource(R.string.pages_podcast_unavailable),
            onBack,
            onRetry,
            modifier,
        )
        return
    }
    val podcast = state.podcast
    val listState = rememberLazyListState()
    LoadAhead(listState, onNearEnd = onLoadMore)
    PageScaffold(
        podcast?.title,
        listState,
        onBack,
        modifier,
        bottom = Space.ListEnd - rowInkBottom(RowLead.None, twoLines = true),
        art = podcast?.art,
        actions = {
            if (podcast != null) MoreButton(podcast.title, onClick = { onMore(podcast) })
        },
    ) {
        item(key = "art", contentType = PageSlot.Art) { PageArt(podcast?.art, MediaKind.Podcast, podcast?.title) }
        item(key = "title", contentType = PageSlot.Title) { PodcastTitle(podcast) }
        if (state.isEmpty) {
            item(key = "empty", contentType = PageSlot.Message) {
                EmptyState(icon = null, stringResource(R.string.pages_podcast_empty))
            }
        } else {
            item(key = "gap", contentType = PageSlot.Actions) {
                Spacer(Modifier.height(Space.HeaderToList - rowInkTop(RowLead.None, twoLines = true)))
            }
        }
        if (state.episodesLoading) {
            trackRowBlocks(lead = RowLead.None, withSubtitle = true)
        } else {
            items(state.episodes, key = { it.key }, contentType = { PageSlot.Track }) { episode ->
                EpisodeRow(
                    episode,
                    download = downloads[episode.key],
                    playing = episode.key == state.currentKey,
                    onClick = { onPlayEpisode(episode) },
                    onMore = { onMoreEpisode(episode) },
                )
            }
            if (state.loadingMore) trackRowBlocks(lead = RowLead.None, withSubtitle = true, count = MORE_BLOCKS)
            if (state.moreFailed) {
                item(key = "more_failed", contentType = PageSlot.Message) {
                    MoreFailed(onRetryMore)
                }
            }
        }
    }
}

@Composable
private fun PodcastTitle(podcast: MediaUi?) {
    val publisher = podcast?.subtitle
    PageTitle(
        title = podcast?.title,
        line = when {
            podcast == null -> ({ TitleLine(text = null, onClick = null) })
            publisher == null -> null
            else -> ({ TitleLine(publisher, onClick = null) })
        },
    )
}

private const val MORE_BLOCKS = 2

/** Where the next episodes would be: why they are not, and Retry, which stays until it is used. */
@Composable
private fun MoreFailed(onRetry: () -> Unit) {
    val style = MaterialTheme.typography.bodyMedium
    val text = stringResource(R.string.pages_podcast_more_failed)
    Row(
        Modifier.fillMaxWidth().padding(start = Keyline.Margin, end = Keyline.Margin, top = Space.Sibling),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).inkStart(text, style),
        )
        TextAction(stringResource(R.string.retry), onRetry)
    }
}

/**
 * The podcast page for [key] (`library://podcast/2`). An episode plays on its own, resumed by the
 * server; the one on the current speaker plays and pauses there.
 */
@Composable
fun PodcastRoute(navigator: Navigator, key: String, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val actions = LocalAppActions.current
    val haptics = LocalHapticFeedback.current
    val vm = viewModel {
        PodcastViewModel(key, graph.pageSource(), graph.mapper, graph.playing.playingUri, graph.messages)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val downloads by graph.downloading.states.collectAsStateWithLifecycle()
    PodcastScreen(
        state = state,
        onBack = { navigator.back() },
        onPlayEpisode = { episode ->
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            graph.playback.playOrPause(episode.key, episode.title)
        },
        onMore = { podcast -> actions.more(ActionTarget.Media(podcast)) },
        onMoreEpisode = { episode -> actions.more(ActionTarget.Media(episode.media(state.podcast))) },
        onLoadMore = vm::loadMore,
        onRetry = rememberRetry(vm::retry),
        modifier = modifier,
        onRetryMore = rememberRetry(vm::retryMore),
        downloads = downloads,
    )
}
