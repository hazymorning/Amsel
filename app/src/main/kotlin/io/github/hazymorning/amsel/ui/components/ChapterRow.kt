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

/** [left]: seconds left in the current chapter, shown in place of its length. */
@Composable
fun ChapterRow(
    chapter: ChapterUi,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** [numberLeadWidth] of the last chapter, so all numbers share one column. */
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
                modifier = Modifier.trimSideBearings(length, style),
            )
        },
    )
}

/**
 * [current] and [left] are read inside the items, so the list is not rebuilt each second while a
 * book plays; only the current row reads [left].
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

@Composable
fun chapterTitle(chapter: ChapterUi): String =
    chapter.title.ifBlank { stringResource(R.string.spoken_chapter, chapter.number) }

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
 * SystemClock.elapsedRealtime, updated once a second while [running]: enough for chapter marks and
 * time left, without recomposing every frame. Driven by the frame clock, which Compose tests pause.
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
