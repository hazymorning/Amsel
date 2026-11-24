package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.listening
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.AppSheet
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.components.MoreButton
import io.github.hazymorning.amsel.ui.components.ProgressLine
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.capToBaseline
import io.github.hazymorning.amsel.ui.components.centreOnGlyphs
import io.github.hazymorning.amsel.ui.components.chapterRows
import io.github.hazymorning.amsel.ui.components.leftText
import io.github.hazymorning.amsel.ui.components.lengthText
import io.github.hazymorning.amsel.ui.components.numberLeadWidth
import io.github.hazymorning.amsel.ui.components.rememberListeningClock
import io.github.hazymorning.amsel.ui.components.rowGlyphBottom
import io.github.hazymorning.amsel.ui.components.rowGlyphTop
import io.github.hazymorning.amsel.ui.model.ChapterUi
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.PlayerControl
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.Route
import io.github.hazymorning.amsel.ui.rememberRetry
import io.github.hazymorning.amsel.ui.theme.Space

@Composable
fun AudiobookScreen(
    state: AudiobookUiState,
    onBack: () -> Unit,
    onResume: () -> Unit,
    onPlayChapter: (ChapterUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onDownload: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onRemoveDownload: () -> Unit = {},
) {
    if (state.showUnavailable) {
        PageUnavailable(
            R.drawable.ic_audiobook,
            stringResource(R.string.pages_audiobook_unavailable),
            onBack,
            onRetry,
            modifier,
        )
        return
    }
    val book = state.book
    val listState = rememberLazyListState()
    val now by rememberListeningClock(state.playback?.isPlaying == true)
    val current by remember(state) { derivedStateOf { state.chapterAt(now) } }
    val left by remember(state) {
        derivedStateOf {
            val at = state.positionAt(now) ?: return@derivedStateOf null
            state.chapters.getOrNull(current)?.let { ((it.endMs - at).coerceAtLeast(0) / 1000).toInt() }
        }
    }
    val numbers = numberLeadWidth(state.chapters.lastOrNull()?.number ?: 0)
    PageScaffold(
        book?.title,
        listState,
        onBack,
        modifier,
        bottom = Space.ListEnd - rowGlyphBottom(RowLead.Glyph, twoLines = false),
        art = book?.media?.art,
        actions = { if (book != null) MoreButton(book.title, onClick = { onMore(book.media) }) },
    ) {
        item(key = "art", contentType = PageSlot.Art) { PageArt(book?.art, MediaKind.Audiobook, book?.title) }
        item(key = "title", contentType = PageSlot.Title) { BookTitle(book, onOpenAuthor) }
        item(key = "where", contentType = PageSlot.Message) { WhereYouAre(state, now) }
        item(key = "actions", contentType = PageSlot.Actions) {
            PageActions(
                playing = state.playback?.isPlaying == true,
                onPlay = onResume,
                playLabel = label(state),
                end = if (state.offersDownload) {
                    downloadGlyph(state.download, onDownload, onCancelDownload, onRemoveDownload)
                } else {
                    null
                },
                listInk = rowGlyphTop(RowLead.Glyph, twoLines = false),
            )
        }
        if (state.chaptersLoading) {
            trackRowBlocks(RowLead.Glyph, withSubtitle = false)
        } else {
            chapterRows(state.chapters, {
                current
            }, state.playback?.isPlaying == true, { left }, numbers, onPlayChapter)
        }
    }
}

@Composable
private fun BookTitle(book: AudiobookUi?, onOpenAuthor: (String) -> Unit) {
    val author = book?.author
    val authorKey = book?.authorKey
    PageTitle(
        title = book?.title,
        line = when {
            book == null -> ({ TitleLine(text = null, onClick = null) })

            author == null -> null

            else -> (
                {
                    TitleLine(
                        author,
                        onClick = authorKey?.let { key -> { onOpenAuthor(key) } },
                        linkLabel = R.string.player_action_go_to_author,
                    )
                }
                )
        },
        meta = book?.narrator?.let { stringResource(R.string.pages_read_by, it) },
        hasMeta = book == null || book.narrator != null,
    )
}

@Composable
private fun WhereYouAre(state: AudiobookUiState, now: Long) {
    val book = state.book ?: return
    val where = listening(book.durationSec, state.positionAt(now), book.fullyPlayed && state.playback == null)
    val type = MaterialTheme.typography.bodyMedium
    val text = when {
        where != null -> leftText(where.second)
        book.fullyPlayed -> stringResource(R.string.pages_finished)
        book.durationSec != null -> lengthText(book.durationSec)
        else -> return
    }
    Column(
        Modifier.fillMaxWidth().wrapContentWidth().width(pageCover()).padding(top = Space.Group),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = type,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.capToBaseline(type).centreOnGlyphs(text, type),
        )
        if (where != null) ProgressLine({ where.first }, Modifier.padding(top = Space.Part))
    }
}

@Composable
private fun label(state: AudiobookUiState): String = stringResource(
    when {
        state.playback?.isPlaying == true -> R.string.pause
        state.started -> R.string.resume
        else -> R.string.play
    },
)

/**
 * [key] is the book's URI, such as `library://audiobook/3`. A play without a start item resumes where
 * the user stopped (docs/API-NOTES.md, "Audiobooks and podcasts").
 */
@Composable
fun AudiobookRoute(navigator: Navigator, key: String, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val actions = LocalAppActions.current
    val haptics = LocalHapticFeedback.current
    val vm = viewModel {
        AudiobookViewModel(key, graph.pageSource(), graph.mapper, graph.playing.nowPlaying, graph.downloading.of(key))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    AudiobookScreen(
        state = state,
        onBack = { navigator.back() },
        // No haptic here: the play button gives its own.
        onResume = { graph.playback.playOrPause(key, state.book?.title) },
        onPlayChapter = { chapter ->
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            val on = state.playback
            when {
                // Already on the speaker: a seek, so nothing restarts, then a play if it is paused.
                on != null -> {
                    graph.playback.control(PlayerControl.Seek(chapter.startMs))
                    if (!on.isPlaying) graph.playback.control(PlayerControl.PlayPause)
                }

                else -> graph.playback.play(
                    listOf(key),
                    startItem = chapter.position,
                    title = state.book?.title,
                    startPositionMs = chapter.startMs,
                )
            }
        },
        onMore = { book -> actions.more(ActionTarget.Media(book)) },
        onOpenAuthor = { navigator.open(Route.Artist(it)) },
        onRetry = rememberRetry(vm::retry),
        modifier = modifier,
        onDownload = { state.book?.media?.let(actions::download) },
        onCancelDownload = { graph.downloading.remove(key) },
        onRemoveDownload = { state.book?.media?.let { actions.open(AppSheet.RemoveDownload(it)) } },
    )
}
