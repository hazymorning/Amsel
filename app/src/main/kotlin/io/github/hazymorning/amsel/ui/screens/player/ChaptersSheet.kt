package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.playback.ChapterNavigation
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.capToBaseline
import io.github.hazymorning.amsel.ui.components.chapterRows
import io.github.hazymorning.amsel.ui.components.numberLeadWidth
import io.github.hazymorning.amsel.ui.components.rememberListeningClock
import io.github.hazymorning.amsel.ui.components.sheetEnd
import io.github.hazymorning.amsel.ui.model.ChapterUi
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.PlayerControl
import io.github.hazymorning.amsel.ui.model.positionAt
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space

/**
 * Chapters, where music has Up next: every chapter of the book or episode playing, the list opening
 * on the chapter before the one it is in. A tap goes to that chapter's start. One without chapters
 * says so.
 */
@Composable
fun ChaptersSheetContent(state: NowPlayingUi, onPick: (ChapterUi) -> Unit, modifier: Modifier = Modifier) {
    val now by rememberListeningClock(state.isPlaying)
    val starts = remember(state.chapters) { state.chapters.map { it.startMs } }
    val at = state.positionAt(now)
    val current = ChapterNavigation.indexAt(starts, at)
    val left = state.chapters.getOrNull(current)?.let { ((it.endMs - at).coerceAtLeast(0) / 1000).toInt() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))
    val end = if (state.chapters.isEmpty()) Space.SheetEdge else sheetEnd(RowLead.Glyph, twoLines = false)
    Column(modifier.fillMaxWidth().padding(bottom = end)) {
        SheetHeader(stringResource(R.string.player_chapters))
        Spacer(Modifier.height(if (state.chapters.isEmpty()) Space.HeaderToContent else Space.HeaderToRows))
        if (state.chapters.isEmpty()) {
            val body = MaterialTheme.typography.bodyLarge
            Text(
                stringResource(
                    if (state.kind == MediaKind.Episode) {
                        R.string.player_no_chapters_episode
                    } else {
                        R.string.player_no_chapters_book
                    },
                ),
                style = body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Keyline.Margin).capToBaseline(body),
            )
        } else {
            val numbers = numberLeadWidth(state.chapters.lastOrNull()?.number ?: 0)
            LazyColumn(state = list) {
                chapterRows(state.chapters, { current }, state.isPlaying, { left }, numbers, onPick)
            }
        }
    }
}

/** The chapters of what plays; a pick goes to its start and closes the sheet. */
@Composable
fun ChaptersRoute(onClose: () -> Unit) {
    val graph = LocalContext.current.graph
    val now by graph.playing.nowPlaying.collectAsStateWithLifecycle()
    val np = now ?: return
    ChaptersSheetContent(np, onPick = { chapter ->
        graph.playback.control(PlayerControl.Seek(chapter.startMs))
        onClose()
    })
}
