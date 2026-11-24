package io.github.hazymorning.amsel.ui.screens.search

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.RecentItem
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.components.MediaRow
import io.github.hazymorning.amsel.ui.components.QuietPill
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.components.SectionHeader
import io.github.hazymorning.amsel.ui.components.TrackRow
import io.github.hazymorning.amsel.ui.components.fadingEdges
import io.github.hazymorning.amsel.ui.components.headerToRow
import io.github.hazymorning.amsel.ui.components.rowGlyphBottom
import io.github.hazymorning.amsel.ui.components.withTop
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import io.github.hazymorning.amsel.ui.model.secondLine
import io.github.hazymorning.amsel.ui.model.twoLines
import io.github.hazymorning.amsel.ui.model.word
import io.github.hazymorning.amsel.ui.theme.Fade
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space

@Immutable
internal class ResultActions(
    val onPlayTrack: (TrackUi) -> Unit,
    val onOpen: (MediaUi) -> Unit,
    val onTrackMore: (TrackUi) -> Unit,
    val onMore: (MediaUi) -> Unit,
)

@Composable
internal fun ResultList(
    results: SearchResultsUi,
    playingKey: String?,
    actions: ResultActions,
    /** Its top inset is already consumed above the search field. */
    insets: PaddingValues,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val hideKeyboard = remember(keyboard) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) keyboard?.hide()
                return Offset.Zero
            }
        }
    }
    // Keyed by query: a new query starts at the top, the same one keeps its scroll across recreation.
    val list = rememberSaveable(results.query, saver = LazyListState.Saver) { LazyListState() }
    ScrollEdges(list)
    LazyColumn(
        Modifier.fillMaxSize().fadingEdges(list, length = Fade.Long).nestedScroll(hideKeyboard),
        state = list,
        contentPadding = insets.withTop(Space.Group, bottom = Space.ListEnd - rowGlyphBottom(RowLead.Cover, true)),
    ) {
        var first = true

        fun LazyListScope.header(key: String, @StringRes title: Int) {
            val spaced = !first
            first = false
            item(key = "header:$key", contentType = "header") {
                SectionHeader(
                    stringResource(title),
                    above = if (spaced) rowGlyphBottom(RowLead.Cover, true) else null,
                    below = headerToRow(RowLead.Cover, true),
                )
            }
        }

        results.top?.let { top ->
            header("top", R.string.search_top_result)
            item(key = "top", contentType = "top") {
                when (top) {
                    // A top result can be of any kind, so its row names it.
                    is TopResult.Song -> TrackRow(
                        top.track,
                        RowLead.Cover,
                        onClick = { actions.onPlayTrack(top.track) },
                        onMore = { actions.onTrackMore(top.track) },
                        playing = top.track.key == playingKey,
                        subtitle = kindLine(R.string.kind_song, top.track.artist),
                    )

                    is TopResult.Item -> MediaRow(
                        top.item,
                        onClick = { actions.onOpen(top.item) },
                        onMore = { actions.onMore(top.item) },
                        playing = top.item.isPlaying(playingKey),
                        subtitle = kindLine(top.item.kind.word, top.item.subtitle),
                    )
                }
            }
        }
        if (results.songs.isNotEmpty()) {
            header("songs", R.string.search_songs)
            items(results.songs, key = { "song:${it.key}" }, contentType = { "song" }) { track ->
                TrackRow(
                    track,
                    RowLead.Cover,
                    onClick = { actions.onPlayTrack(track) },
                    onMore = { actions.onTrackMore(track) },
                    playing = track.key == playingKey,
                )
            }
        }
        listOf(
            Triple("albums", PlaybackR.string.albums, results.albums),
            Triple("artists", PlaybackR.string.artists, results.artists),
            Triple("playlists", PlaybackR.string.playlists, results.playlists),
            Triple("radio", R.string.radio, results.radio),
            Triple("audiobooks", PlaybackR.string.audiobooks, results.audiobooks),
            Triple("podcasts", PlaybackR.string.podcasts, results.podcasts),
        ).forEach { (key, title, list) ->
            if (list.isNotEmpty()) {
                header(key, title)
                val twoLines = list.twoLines()
                items(list, key = { "$key:${it.key}" }, contentType = { "media" }) { item ->
                    MediaRow(
                        item,
                        onClick = { actions.onOpen(item) },
                        onMore = { actions.onMore(item) },
                        playing = item.isPlaying(playingKey),
                        subtitle = if (twoLines) item.secondLine() else null,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RecentList(
    recent: List<RecentItem>,
    playingKey: String?,
    actions: ResultActions,
    onClear: () -> Unit,
    insets: PaddingValues,
) {
    val list = rememberLazyListState()
    ScrollEdges(list)
    LazyColumn(
        Modifier.fillMaxSize().fadingEdges(list, length = Fade.Long),
        state = list,
        contentPadding = insets.withTop(Space.Group, bottom = Space.ListEnd),
    ) {
        item(key = "header", contentType = "header") {
            val below = headerToRow(RowLead.Cover, twoLines = true)
            SectionHeader(stringResource(R.string.search_recent), above = null, below = below)
        }
        items(recent, key = { it.key }, contentType = { it::class }) { recentItem ->
            when (recentItem) {
                is RecentItem.Song -> TrackRow(
                    recentItem.track,
                    RowLead.Cover,
                    onClick = { actions.onPlayTrack(recentItem.track) },
                    onMore = { actions.onTrackMore(recentItem.track) },
                    playing = recentItem.key == playingKey,
                )

                is RecentItem.Other -> MediaRow(
                    recentItem.item,
                    onClick = { actions.onOpen(recentItem.item) },
                    onMore = { actions.onMore(recentItem.item) },
                    playing = recentItem.item.isPlaying(playingKey),
                )
            }
        }
        item(key = "clear", contentType = "clear") {
            Box(Modifier.fillMaxWidth().padding(start = Keyline.Margin, top = Space.S12)) {
                QuietPill(R.drawable.ic_close, stringResource(R.string.search_recent_clear), onClear)
            }
        }
    }
}

/** Only a station plays on tap; other items open a page, so only a station shows as playing. */
private fun MediaUi.isPlaying(playingKey: String?): Boolean = kind == MediaKind.Radio && key == playingKey

@Composable
private fun kindLine(@StringRes kind: Int, detail: String?): String {
    val name = stringResource(kind)
    return if (detail.isNullOrBlank()) name else "$name$DOT_SEPARATOR$detail"
}
