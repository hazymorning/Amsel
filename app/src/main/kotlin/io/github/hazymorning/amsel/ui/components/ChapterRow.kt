package io.github.hazymorning.amsel.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.model.ChapterUi
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.tabular

/**
 * A chapter as a track row: its number (the playing mark while it plays), its title and its length.
 * The chapter the person is in is the list's current one, selected for TalkBack and with what is
 * [left] of it in place of its length; one already [heard] is quiet. A tap plays from it.
 */
@Composable
fun ChapterRow(
    chapter: ChapterUi,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The number column, [numberLeadWidth] of the list's last chapter. */
    leadWidth: Dp = Sizes.Lead,
    left: Int? = null,
    heard: Boolean = false,
) {
    val type = MaterialTheme.typography
    val length = left?.let { stringResource(R.string.spoken_chapter_left, formatDuration(it)) }
        ?: formatDuration(chapter.durationSec)
    ListRow(
        title = chapterTitle(chapter),
        modifier = modifier.then(if (current) Modifier.semantics { selected = true } else Modifier),
        lead = RowLead.Glyph,
        playing = playing,
        quiet = heard,
        onClick = onClick,
        leadWidth = leadWidth,
        leading = { TrackNumber(chapter.number, playing, onTitleLine = false) },
        trailing = {
            val style = type.bodyMedium.tabular()
            Text(
                length,
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.inkSides(length, style),
            )
        },
    )
}

/**
 * Every chapter as a [ChapterRow], their numbers in one column [leadWidth] wide ([numberLeadWidth]);
 * [current] is the index of the chapter the person is in ([playing] while it plays), with what is
 * [left] of it; those before it are heard. Both are read in the rows, so the list is not built anew
 * each second while a book plays, and only the current row reads what is left.
 */
fun LazyListScope.chapterRows(
    chapters: List<ChapterUi>,
    current: () -> Int,
    playing: Boolean,
    left: () -> Int?,
    leadWidth: Dp,
    onClick: (ChapterUi) -> Unit,
) {
    itemsIndexed(chapters, key = { i, _ -> "chapter_$i" }, contentType = { _, _ -> "chapter" }) { i, chapter ->
        val at = current()
        ChapterRow(
            chapter,
            current = i == at,
            playing = i == at && playing,
            onClick = { onClick(chapter) },
            leadWidth = leadWidth,
            left = if (i == at) left() else null,
            heard = i < at,
        )
    }
}

/** The chapter's own title, or "Chapter 3" when it has none. */
@Composable
fun chapterTitle(chapter: ChapterUi): String =
    chapter.title.ifBlank { stringResource(R.string.spoken_chapter, chapter.number) }

/**
 * "Chapter 3 · The Spouter-Inn", or the title alone when it already says which chapter it is, or
 * "Chapter 3" when it has none.
 */
@Composable
fun chapterLine(chapter: ChapterUi): String {
    val number = stringResource(R.string.spoken_chapter, chapter.number)
    return when {
        chapter.title.isBlank() -> number
        chapter.title.equals(number, ignoreCase = true) -> chapter.title
        else -> stringResource(R.string.spoken_chapter_titled, chapter.number, chapter.title)
    }
}

/**
 * The phone's clock (SystemClock.elapsedRealtime), moving on once a second while [running]: enough
 * for a chapter mark and the time left to follow playback without drawing every frame. It runs on
 * the frame clock, which Compose pauses in tests.
 */
@Composable
fun rememberListeningClock(running: Boolean): State<Long> {
    val now = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(running) {
        now.longValue = SystemClock.elapsedRealtime()
        while (running) {
            withInfiniteAnimationFrameMillis { }
            val at = SystemClock.elapsedRealtime()
            if (at / TICK_MS != now.longValue / TICK_MS) now.longValue = at
        }
    }
    return now
}

private const val TICK_MS = 1000L
