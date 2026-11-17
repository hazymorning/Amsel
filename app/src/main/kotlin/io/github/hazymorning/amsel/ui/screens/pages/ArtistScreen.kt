package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.POPULAR_TRACKS
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.components.MediaTile
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.SectionHeader
import io.github.hazymorning.amsel.ui.components.SectionHeaderPlaceholder
import io.github.hazymorning.amsel.ui.components.TrackRow
import io.github.hazymorning.amsel.ui.components.headerToRow
import io.github.hazymorning.amsel.ui.components.rowInkBottom
import io.github.hazymorning.amsel.ui.components.tileColumns
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import io.github.hazymorning.amsel.ui.model.ownPage
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.openMedia
import io.github.hazymorning.amsel.ui.rememberRetry
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space
import kotlinx.coroutines.flow.map

@Composable
fun ArtistScreen(
    state: ArtistUiState,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (TrackUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    onMoreTrack: (TrackUi) -> Unit,
    onOpen: (MediaUi) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.showUnavailable) {
        PageUnavailable(
            R.drawable.ic_artist,
            stringResource(R.string.pages_artist_unavailable),
            onBack,
            onRetry,
            modifier,
        )
    } else {
        val artist = state.artist
        val listState = rememberLazyListState()
        // The page is as wide as the window, as its cover and title are ([pageCover]).
        val columns = tileColumns(LocalWindowInfo.current.containerDpSize.width)
        val rows = remember(state.albums, columns) { state.albums.chunked(columns) }
        val similarTitle = stringResource(R.string.pages_similar_artists)
        val shelfAbove = when {
            rows.isNotEmpty() || state.contentLoading -> Space.TileRows
            state.popular.isNotEmpty() || state.popularPending -> rowInkBottom(RowLead.Cover, true)
            else -> Space.HeaderToList
        }
        // A tile's caption ends on its ink; an album row keeps its own room under it.
        val bottom = if (state.similar.isEmpty()) Space.ListEnd - Space.TileRows else Space.ListEnd
        PageScaffold(artist?.title, listState, onBack, modifier.fillMaxSize(), bottom = bottom, art = artist?.art) {
            item(key = "art", contentType = PageSlot.Art) {
                PageArt(artist?.art, MediaKind.Artist, artist?.title)
            }
            item(key = "title", contentType = PageSlot.Title) { PageTitle(artist?.title) }
            item(key = "actions", contentType = PageSlot.Actions) {
                PageActions(
                    playing = false,
                    onPlay = onPlay,
                    start = shuffleGlyph(onShuffle),
                    end = artist?.let { moreGlyph(it.title) { onMore(it) } },
                )
            }
            if (state.contentLoading) {
                popularBlocks()
                albumBlocks(columns)
            } else if (state.popularPending) {
                // Popular songs answer last: their place is held so the albums never move.
                popularHeader()
                trackRowBlocks(RowLead.Cover, withSubtitle = true, count = POPULAR_TRACKS)
                albumGrid(rows, columns, afterSongs = true, onOpen, onMore)
            } else {
                popular(state, onPlayTrack, onMoreTrack)
                albumGrid(rows, columns, afterSongs = state.popular.isNotEmpty(), onOpen, onMore)
            }
            pageShelf(similarTitle, state.similar, shelfAbove, onOpen, onMore, twoLines = false)
        }
    }
}

private fun LazyListScope.popular(
    state: ArtistUiState,
    onPlayTrack: (TrackUi) -> Unit,
    onMoreTrack: (TrackUi) -> Unit,
) {
    if (state.popular.isEmpty()) return
    popularHeader()
    items(state.popular, key = { "popular:${it.key}" }, contentType = { PageSlot.Track }) { track ->
        TrackRow(
            track,
            RowLead.Cover,
            onClick = { onPlayTrack(track) },
            onMore = { onMoreTrack(track) },
            playing = track.key == state.currentTrackKey,
            subtitle = state.subtitleOf(track),
        )
    }
}

private fun LazyListScope.popularHeader() {
    item(key = "popular_header", contentType = PageSlot.Header) {
        SectionHeader(
            stringResource(R.string.pages_popular),
            above = Space.HeaderToList,
            below = headerToRow(RowLead.Cover, true),
        )
    }
}

/**
 * Albums laid out by hand in rows: a lazy grid cannot sit inside the page's lazy column. The header
 * keeps a section's distance from the last ink above it, popular songs' or the page's play.
 */
private fun LazyListScope.albumGrid(
    rows: List<List<MediaUi>>,
    columns: Int,
    afterSongs: Boolean,
    onOpen: (MediaUi) -> Unit,
    onMore: (MediaUi) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "albums_header", contentType = PageSlot.Header) {
        SectionHeader(
            stringResource(PlaybackR.string.albums),
            above = if (afterSongs) rowInkBottom(RowLead.Cover, true) else Space.HeaderToList,
        )
    }
    items(rows, key = { "albums:${it.first().key}" }, contentType = { PageSlot.AlbumRow }) { row ->
        Row(
            Modifier.fillMaxWidth().padding(start = Keyline.Margin, end = Keyline.Margin, bottom = Space.TileRows),
            horizontalArrangement = Arrangement.spacedBy(Space.Tiles),
        ) {
            row.forEach { album ->
                MediaTile(album, onClick = { onOpen(album) }, onMore = { onMore(album) }, Modifier.weight(1f))
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private fun LazyListScope.popularBlocks() {
    item(key = "popular_header_block", contentType = PageSlot.Header) {
        SectionHeaderPlaceholder(above = Space.HeaderToList, below = headerToRow(RowLead.Cover, true))
    }
    trackRowBlocks(RowLead.Cover, withSubtitle = true, count = POPULAR_TRACKS)
}

private fun LazyListScope.albumBlocks(columns: Int) {
    item(key = "albums_header_block", contentType = PageSlot.Header) {
        SectionHeaderPlaceholder(above = rowInkBottom(RowLead.Cover, true))
    }
    item(key = "albums_block", contentType = PageSlot.AlbumRow) {
        TileRowBlock(columns, Modifier.padding(bottom = Space.TileRows))
    }
}

/** The artist page for [key] (`library://artist/3`), wired to the server and the current speaker. */
@Composable
fun ArtistRoute(navigator: Navigator, key: String, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val actions = LocalAppActions.current
    val haptics = LocalHapticFeedback.current
    val vm = viewModel {
        val similar = graph.data.extras.offered.map { it.similarArtists }
        ArtistViewModel(key, graph.pageSource(), graph.mapper, graph.playing.playingUri, graph.messages, similar)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    fun play(shuffle: Boolean) = graph.playback.play(listOf(key), shuffle = shuffle, title = state.artist?.title)
    ArtistScreen(
        state = state,
        onBack = { navigator.back() },
        onPlay = { play(shuffle = false) },
        onShuffle = { play(shuffle = true) },
        onPlayTrack = { track ->
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            // A popular song plays the popular songs from there, not the whole discography. The server
            // takes start_item only for an album or a playlist, so the list starts at the song.
            val from = state.popular.indexOf(track).coerceAtLeast(0)
            graph.playback.play(state.popular.drop(from).map { it.key }, title = track.title)
        },
        // "Go to artist" from this artist's albums and songs would lead back to this page: left out.
        onMore = { item ->
            val media = item.copy(artistKey = item.artistKey.takeUnless { it == key })
            actions.more(ActionTarget.Media(media, ownPage(item.key == key)))
        },
        onMoreTrack = { track ->
            actions.more(ActionTarget.Track(track.copy(artistKey = track.artistKey.takeUnless { it == key })))
        },
        onOpen = { item -> navigator.openMedia(item) },
        onRetry = rememberRetry(vm::retry),
        modifier = modifier,
    )
}
