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
import io.github.hazymorning.amsel.playback.ChapterNavigation
import io.github.hazymorning.amsel.ui.components.capToBaseline
import io.github.hazymorning.amsel.ui.components.formatDuration
import io.github.hazymorning.amsel.ui.components.trimSideBearings
import io.github.hazymorning.amsel.ui.model.ChapterUi
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.positionAt
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Space
import kotlin.math.abs
import kotlinx.coroutines.delay

// Advances only as often as it shows, about a pixel of the line and at least four times a second,
// sleeping in between. Each step waits for an infinite-animation frame, which Compose pauses in
// tests, so screenshots hold at [NowPlayingUi.positionMs].
@Composable
internal fun rememberPlaybackPosition(state: NowPlayingUi): State<Long> {
    val position = remember { mutableLongStateOf(state.positionMs) }
    LaunchedEffect(state.positionMs, state.positionAtMs, state.runs, state.durationMs) {
        position.longValue = state.positionMs
        val step = (state.durationMs / LINE_STEPS).coerceIn(MIN_STEP_MS, MAX_STEP_MS)
        val pace = state.speed.toDouble().takeIf { it > 0 } ?: 1.0
        while (state.runs) {
            withInfiniteAnimationFrameMillis { }
            val now = state.positionAt(SystemClock.elapsedRealtime())
            if (abs(now - position.longValue) >= step) position.longValue = now
            // At the end the position holds until the next report.
            if (state.durationMs in 1..now) break
            delay(((position.longValue + step - now) / pace).toLong().coerceAtLeast(1))
        }
    }
    return position
}

// About the line's width in pixels on a phone.
private const val LINE_STEPS = 1000L
private const val MIN_STEP_MS = 16L
private const val MAX_STEP_MS = 250L

// After a seek the line holds the released place until the next report. The times show always for
// spoken word, as the owner asked, and for music while seeking and shortly after; they take no
// height, so nothing below moves.
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
    // While held, the line keeps the chapter it was grabbed in, though playback moves on.
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
    // A station is a live stream: line and times are invisible but keep their space, so nothing moves.
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
            // Composed only while visible, so TalkBack never lands on hidden times.
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

// A true minus, which sits at the figures' height, unlike a hyphen.
private const val MINUS = "\u2212"
private const val MINUS_TIGHTEN = -0.12f

/** Null unless the item has two or more chapters. */
@Composable
internal fun rememberChapter(state: NowPlayingUi, position: State<Long>): State<ChapterUi?> {
    val starts = remember(state.chapters) { state.chapters.map { it.startMs } }
    return remember(state.chapters) {
        derivedStateOf {
            if (!state.hasChapters) {
                null
            } else {
                state.chapters.getOrNull(
                    ChapterNavigation.indexAt(starts, position.value),
                )
            }
        }
    }
}

@Composable
private fun TimeLabel(text: String) {
    val style = MaterialTheme.typography.labelMedium
    // The minus is as wide as a figure; tightened a little, it reads as part of its time.
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
        modifier = Modifier.capToBaseline(style).trimSideBearings(text, style),
    )
}
