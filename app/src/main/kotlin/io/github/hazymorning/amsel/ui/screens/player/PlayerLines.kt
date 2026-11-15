package io.github.hazymorning.amsel.ui.screens.player

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.playback.Chapters
import io.github.hazymorning.amsel.ui.components.formatDuration
import io.github.hazymorning.amsel.ui.components.inkSides
import io.github.hazymorning.amsel.ui.components.textInk
import io.github.hazymorning.amsel.ui.model.ChapterUi
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.positionAt
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Space
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * The playback position, moving on with the frame clock while playing, but only as often as it
 * shows: about once a pixel of the cover's line, and a few times a second at least for the times,
 * so the screen is not drawn anew every frame for a line that has not moved. The clock runs as an
 * infinite animation, which Compose pauses in tests, so screenshots stay still at [NowPlayingUi.positionMs].
 */
@Composable
internal fun rememberPlaybackPosition(state: NowPlayingUi): State<Long> {
    val position = remember { mutableLongStateOf(state.positionMs) }
    LaunchedEffect(state.positionMs, state.positionAtMs, state.runs, state.durationMs) {
        position.longValue = state.positionMs
        val step = (state.durationMs / LINE_STEPS).coerceIn(MIN_STEP_MS, MAX_STEP_MS)
        while (state.runs) {
            withInfiniteAnimationFrameMillis { }
            val now = state.positionAt(SystemClock.elapsedRealtime())
            if (abs(now - position.longValue) >= step) position.longValue = now
        }
    }
    return position
}

/** Steps along the line: about its width in pixels on a phone. */
private const val LINE_STEPS = 1000L
private const val MIN_STEP_MS = 16L
private const val MAX_STEP_MS = 250L

/**
 * Where the music is, on a line under the words ([LineSlider] with no resting thumb). The seek is
 * sent once, on release, and the line stays there until the next report. A book or episode with
 * chapters runs over the chapter it is in, not the whole item. The time played and the time left
 * stand under the line, in figures of one width: always for a book or an episode, where they say
 * where the person is (the owner's wish), and for music while the line is moved and a moment after.
 * They take no room of their own, so nothing below them moves.
 */
@Composable
internal fun SeekBar(
    state: NowPlayingUi,
    position: State<Long>,
    /** The chapter [position] is in ([rememberChapter]). */
    chapter: State<ChapterUi?>,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var held by remember { mutableStateOf(false) }
    val live by chapter
    // While the line is held it keeps the chapter it was taken in, though playback moves on.
    var taken by remember { mutableStateOf<ChapterUi?>(null) }
    val shown = if (held) taken ?: live else live
    val from = shown?.startMs ?: 0L
    val duration = shown?.let { it.endMs - it.startMs } ?: state.durationMs
    val seekable = duration > 0
    var scrub by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(state.positionMs, state.positionAtMs) { if (!held) scrub = null }
    val seconds by remember(from, duration) {
        derivedStateOf {
            scrub?.let { (it * duration).toLong() / 1000 } ?: ((position.value - from).coerceAtLeast(0) / 1000)
        }
    }
    val total = (duration / 1000).toInt()
    val elapsed = formatDuration(seconds.toInt())
    val left = if (seekable) MINUS + formatDuration((total - seconds.toInt()).coerceAtLeast(0)) else ""
    val fraction = {
        if (seekable) scrub ?: ((position.value - from).toFloat() / duration).coerceIn(0f, 1f) else 0f
    }
    val at = if (seekable) stringResource(R.string.player_position_of, elapsed, formatDuration(total)) else elapsed
    val where = shown?.let { stringResource(R.string.player_chapter_position, it.number, state.chapters.size, at) }
    var lately by remember { mutableStateOf(false) }
    LaunchedEffect(held) {
        if (held) {
            lately = true
        } else {
            delay(TIMES_LINGER_MS)
            lately = false
        }
    }
    val timed = state.spoken || held || lately
    val times by animateFloatAsState(if (timed) 1f else 0f, Motion.effects(), label = "times")
    // A station is one live stream: no line and no times, their room kept so nothing below moves.
    val stream = state.kind == MediaKind.Radio
    Layout(
        content = {
            LineSlider(
                fraction,
                semanticValue = if (seekable) seconds * 1000f / duration else 0f,
                label = stringResource(R.string.player_position),
                stateDescription = where ?: at,
                onChange = {
                    if (!held) taken = live
                    held = true
                    scrub = it
                },
                onChangeFinished = {
                    held = false
                    scrub = it
                    onSeek(from + (it * duration).toLong())
                    taken = null
                },
                onChangeCancelled = {
                    held = false
                    scrub = null
                    taken = null
                },
                enabled = seekable && state.canSeek,
                restingThumb = false,
                pending = state.arriving,
            )
            // Composed only while it shows, so TalkBack never lands on times no one can see.
            if (timed || times > 0f) {
                // The line itself says both times to TalkBack.
                Row(Modifier.fillMaxWidth().graphicsLayer { alpha = times }.clearAndSetSemantics { }) {
                    TimeLabel(elapsed)
                    Spacer(Modifier.weight(1f))
                    TimeLabel(left)
                }
            }
        },
        modifier = if (stream) modifier.alpha(0f).clearAndSetSemantics {} else modifier,
    ) { measurables, constraints ->
        val line = measurables[0].measure(constraints)
        val labels = measurables.getOrNull(1)?.measure(constraints.copy(minHeight = 0))
        val below = line.height + Space.S3.roundToPx()
        layout(line.width, line.height) {
            line.place(0, 0)
            labels?.place(0, below)
        }
    }
}

private const val TIMES_LINGER_MS = 1_500L

/** The time left starts with a true minus, which sits at the figures' height. */
private const val MINUS = "\u2212"
private const val MINUS_TIGHTEN = -0.12f

/** The chapter [position] is in, for a book or episode with two or more; null otherwise. */
@Composable
internal fun rememberChapter(state: NowPlayingUi, position: State<Long>): State<ChapterUi?> {
    val starts = remember(state.chapters) { state.chapters.map { it.startMs } }
    return remember(state.chapters) {
        derivedStateOf {
            if (!state.hasChapters) null else state.chapters.getOrNull(Chapters.indexAt(starts, position.value))
        }
    }
}

@Composable
private fun TimeLabel(text: String) {
    val style = MaterialTheme.typography.labelMedium
    // The minus is as wide as a figure; closed up a little it belongs to its time.
    val words = if (text.startsWith(MINUS)) {
        buildAnnotatedString {
            withStyle(SpanStyle(letterSpacing = MINUS_TIGHTEN.em)) { append(MINUS) }
            append(text.removePrefix(MINUS))
        }
    } else {
        AnnotatedString(text)
    }
    Text(
        words,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.textInk(style).inkSides(text, style),
    )
}
