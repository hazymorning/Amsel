package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.ItemUri
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.components.EmptyState
import io.github.hazymorning.amsel.ui.components.MoreButton
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.TrackRow
import io.github.hazymorning.amsel.ui.components.rowGlyphBottom
import io.github.hazymorning.amsel.ui.components.rowGlyphTop
import io.github.hazymorning.amsel.ui.model.ActionsContext
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import io.github.hazymorning.amsel.ui.model.ownPage
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.rememberRetry
import io.github.hazymorning.amsel.ui.theme.Space
import kotlinx.coroutines.flow.filter

@Composable
fun PlaylistScreen(
    state: PlaylistUiState,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (index: Int, track: TrackUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    /** With the row's index, as a playlist can hold the same track twice. */
    onMoreTrack: (index: Int, track: TrackUi) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.showUnavailable) {
        PageUnavailable(
            R.drawable.ic_playlist,
            stringResource(R.string.pages_playlist_unavailable),
            onBack,
            onRetry,
            modifier,
        )
    } else {
        val playlist = state.playlist
        val listState = rememberLazyListState()
        PageScaffold(
            playlist?.title,
            listState,
            onBack,
            modifier,
            bottom =
                Space.ListEnd - rowGlyphBottom(RowLead.Cover, true),
            art = playlist?.art,
            // An empty playlist has no actions row, so its ⋮ goes in the bar.
            actions = {
                playlist?.takeIf { state.isEmpty }?.let { MoreButton(it.title, onClick = { onMore(it.media) }) }
            },
        ) {
            item(key = "art", contentType = PageSlot.Art) {
                PageArt(playlist?.art, MediaKind.Playlist, playlist?.title)
            }
            item(key = "title", contentType = PageSlot.Title) { PlaylistTitle(state) }
            if (state.isEmpty) {
                item(key = "empty", contentType = PageSlot.Message) {
                    EmptyState(icon = null, stringResource(R.string.pages_playlist_empty))
                }
            } else {
                item(key = "actions", contentType = PageSlot.Actions) {
                    PageActions(
                        playing = false,
                        onPlay = onPlay,
                        start = shuffleGlyph(onShuffle),
                        end = playlist?.let { moreGlyph(it.title) { onMore(it.media) } },
                        listInk = rowGlyphTop(RowLead.Cover, twoLines = true),
                    )
                }
            }
            if (state.tracksLoading) {
                trackRowBlocks(RowLead.Cover, withSubtitle = true)
            } else {
                itemsIndexed(
                    state.tracks,
                    key = { index, _ -> state.rowKeys[index] },
                    contentType = { _, _ -> PageSlot.Track },
                ) { index, track ->
                    TrackRow(
                        track,
                        RowLead.Cover,
                        onClick = { onPlayTrack(index, track) },
                        onMore = { onMoreTrack(index, track) },
                        playing = track.key == state.currentTrackKey,
                        showAlbum = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistTitle(state: PlaylistUiState) {
    val playlist = state.playlist
    val owner = playlist?.owner
    PageTitle(
        title = playlist?.title,
        line = when {
            playlist == null -> ({ TitleLine(text = null, onClick = null) })
            owner == null -> null
            else -> ({ TitleLine(stringResource(R.string.pages_playlist_by, owner), onClick = null) })
        },
        meta = if (state.tracksLoading || !state.complete) null else songsAndLength(state.songCount, state.durationSec),
        // An empty playlist says so below instead of "0 songs".
        hasMeta = !state.isEmpty,
    )
}

/** [key] is the playlist's URI, such as `library://playlist/7`. */
@Composable
fun PlaylistRoute(navigator: Navigator, key: String, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val actions = LocalAppActions.current
    val haptics = LocalHapticFeedback.current
    val vm =
        viewModel {
            val edits = graph.playlists.edits.filter { it.playlistKey == key }
            PlaylistViewModel(key, graph.pageSource(), graph.mapper, graph.playing.playingUri, edits, graph.messages)
        }
    val state by vm.state.collectAsStateWithLifecycle()

    fun play(shuffle: Boolean) = graph.playback.play(listOf(key), shuffle = shuffle, title = state.playlist?.title)
    PlaylistScreen(
        state = state,
        onBack = { navigator.back() },
        onPlay = { play(shuffle = false) },
        onShuffle = { play(shuffle = true) },
        onPlayTrack = { index, track ->
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            // The server starts a playlist at start_item's first copy, so a later copy plays as a list
            // from there (docs/API-NOTES.md, "play_media").
            if (state.tracks.take(index).none { it.key == track.key }) {
                graph.playback.play(listOf(key), startItem = track.key, title = track.title)
            } else {
                graph.playback.play(state.tracks.drop(index).map { it.key }, title = track.title)
            }
        },
        onMore = { item -> actions.more(ActionTarget.Media(item, ownPage(item.key == key))) },
        onMoreTrack = { index, track ->
            // Edits need a library playlist's id (docs/API-NOTES.md, "Playlist editing"), and positions
            // hold only once every page is in: a removal before then could name the wrong row.
            val target = if (state.playlist?.editable == true && state.complete &&
                ItemUri.parse(key)?.isLibraryItem == true
            ) {
                ActionTarget.Track(
                    track,
                    ActionsContext.EditablePlaylist,
                    playlistKey = key,
                    position = state.positionAt(index),
                    playlistName = state.playlist?.title,
                )
            } else {
                ActionTarget.Track(track)
            }
            actions.more(target)
        },
        onRetry = rememberRetry(vm::retry),
        modifier = modifier,
    )
}
