package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.foundation.lazy.items
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
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.TrackRow
import io.github.hazymorning.amsel.ui.components.numberLeadWidth
import io.github.hazymorning.amsel.ui.components.rowGlyphBottom
import io.github.hazymorning.amsel.ui.components.rowGlyphTop
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import io.github.hazymorning.amsel.ui.model.ownPage
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.Route
import io.github.hazymorning.amsel.ui.navigation.openMedia
import io.github.hazymorning.amsel.ui.rememberRetry
import io.github.hazymorning.amsel.ui.theme.Space

@Composable
fun AlbumScreen(
    state: AlbumUiState,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (TrackUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    onMoreTrack: (TrackUi) -> Unit,
    onOpen: (MediaUi) -> Unit,
    onOpenArtist: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.showUnavailable) {
        PageUnavailable(
            R.drawable.ic_album,
            stringResource(R.string.pages_album_unavailable),
            onBack,
            onRetry,
            modifier,
        )
    } else {
        val album = state.album
        val listState = rememberLazyListState()
        // A compilation names every track's artist on a second line; elsewhere a guest follows the title.
        val twoLines = album?.compilation == true
        val numbers = numberLeadWidth(state.tracks.maxOfOrNull { it.number ?: 0 } ?: 0)
        val trackInk = rowGlyphBottom(RowLead.Glyph, twoLines)
        val moreBy = album?.artist?.let { stringResource(R.string.pages_more_by, it) }
        // A tile's caption ends at its last glyph; a track row extends below its last glyph.
        val endsOnShelf = moreBy != null && state.moreByArtist.isNotEmpty()
        val bottom = if (endsOnShelf) Space.ListEnd else Space.ListEnd - trackInk
        PageScaffold(album?.title, listState, onBack, modifier, bottom = bottom, art = album?.art) {
            item(key = "art", contentType = PageSlot.Art) { PageArt(album?.art, MediaKind.Album, album?.title) }
            item(key = "title", contentType = PageSlot.Title) { AlbumTitle(state, onOpenArtist) }
            item(key = "actions", contentType = PageSlot.Actions) {
                PageActions(
                    playing = false,
                    onPlay = onPlay,
                    start = shuffleGlyph(onShuffle),
                    end = album?.let { moreGlyph(it.title) { onMore(it.media) } },
                    listInk = rowGlyphTop(RowLead.Glyph, twoLines),
                )
            }
            if (state.tracksLoading) {
                // Most albums have one artist, so their rows are single lines; a compilation's are not.
                trackRowBlocks(RowLead.Glyph, withSubtitle = twoLines)
            } else {
                items(state.tracks, key = { it.key }, contentType = { PageSlot.Track }) { track ->
                    TrackRow(
                        track,
                        RowLead.Glyph,
                        onClick = { onPlayTrack(track) },
                        onMore = { onMoreTrack(track) },
                        playing = track.key == state.currentTrackKey,
                        subtitle = state.subtitleOf(track),
                        twoLines = twoLines,
                        leadWidth = numbers,
                    )
                }
            }
            if (moreBy != null) pageShelf(moreBy, state.moreByArtist, trackInk, onOpen, onMore)
        }
    }
}

@Composable
private fun AlbumTitle(state: AlbumUiState, onOpenArtist: (String) -> Unit) {
    val album = state.album
    val artist = album?.artist
    val artistKey = album?.artistKey
    val year = album?.year?.toString()
    PageTitle(
        title = album?.title,
        line = when {
            album == null -> ({ TitleLine(text = null, onClick = null) })
            artist == null -> null
            else -> ({ TitleLine(artist, onClick = artistKey?.let { key -> { onOpenArtist(key) } }) })
        },
        meta = if (state.tracksLoading) null else songsAndLength(state.songCount, state.durationSec, lead = year),
        hasMeta = true,
    )
}

/** [key] is the album's URI, such as `library://album/12`. */
@Composable
fun AlbumRoute(navigator: Navigator, key: String, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val actions = LocalAppActions.current
    val haptics = LocalHapticFeedback.current
    val vm =
        viewModel { AlbumViewModel(key, graph.pageSource(), graph.mapper, graph.playing.playingUri, graph.messages) }
    val state by vm.state.collectAsStateWithLifecycle()

    fun play(shuffle: Boolean) = graph.playback.play(listOf(key), shuffle = shuffle, title = state.album?.title)
    AlbumScreen(
        state = state,
        onBack = { navigator.back() },
        onPlay = { play(shuffle = false) },
        onShuffle = { play(shuffle = true) },
        onPlayTrack = { track ->
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            graph.playback.play(listOf(key), startItem = track.key, title = track.title)
        },
        // This album's own ⋮ and a "More by" tile's, which is another album.
        onMore = { item -> actions.more(ActionTarget.Media(item, ownPage(item.key == key))) },
        // "Go to album" would lead back to this page, so it is left out.
        onMoreTrack = { track ->
            actions.more(ActionTarget.Track(track.copy(albumKey = track.albumKey.takeUnless { it == key })))
        },
        onOpen = { item -> navigator.openMedia(item) },
        onOpenArtist = { artist -> navigator.open(Route.Artist(artist)) },
        onRetry = rememberRetry(vm::retry),
        modifier = modifier,
    )
}
