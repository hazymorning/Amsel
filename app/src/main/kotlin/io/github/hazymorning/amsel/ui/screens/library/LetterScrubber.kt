package io.github.hazymorning.amsel.ui.screens.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.inkCentre
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import java.text.Normalizer
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the thumb stays after the list stops, so it can still be caught. */
private const val LINGER_MS = 1500L

private val ThumbWidth = Space.S1
private val ThumbLength = Space.S10

private val ThumbInset = (Keyline.Margin - ThumbWidth) / 2

private val Bubble = Space.S16

/** The bubble centres one touch target in from the margin, clear of the finger on the thumb. */
private val BubbleEndInset = Keyline.Margin + Sizes.TouchTarget - Bubble / 2

private val Lane = Keyline.Margin + Space.S3

/**
 * A–Z: a thin thumb in the right margin appears while the list moves; dragging it jumps by letter
 * and shows the letter in a bubble beside it. At rest nothing is drawn. For TalkBack it is one
 * adjustable control ("Jump to letter, M").
 */
@Composable
private fun LetterScrubber(
    letters: List<LetterJump>,
    scrolling: () -> Boolean,
    progress: () -> Float,
    firstVisible: () -> Int,
    onJump: (LetterJump) -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val jump by rememberUpdatedState(onJump)
    val stop by rememberUpdatedState(onHold)
    val current by remember(letters) {
        derivedStateOf { letters.indexOfLast { it.index <= firstVisible() }.coerceAtLeast(0) }
    }
    var held by remember { mutableStateOf<Int?>(null) }
    var heldFraction by remember { mutableFloatStateOf(0f) }
    val moving by remember { derivedStateOf { scrolling() } }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(moving, held) {
        if (moving || held != null) {
            shown = true
        } else {
            delay(LINGER_MS)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, Motion.effects(), label = "thumb")
    val label = stringResource(R.string.library_scrubber)
    val colors = MaterialTheme.colorScheme

    BoxWithConstraints(modifier.width(Lane).fillMaxHeight()) {
        val density = LocalDensity.current
        val travel = with(density) { (maxHeight - ThumbLength).toPx() }.coerceAtLeast(1f)
        val thumbHalf = with(density) { ThumbLength.toPx() / 2 }

        fun pick(y: Float) {
            heldFraction = ((y - thumbHalf) / travel).coerceIn(0f, 1f)
            val i = (heldFraction * letters.size).toInt().coerceIn(0, letters.lastIndex)
            if (i != held) {
                held = i
                jump(letters[i])
            }
        }

        Box(
            Modifier
                .matchParentSize()
                .clearAndSetSemantics {
                    contentDescription = label
                    stateDescription = letters[current].letter
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current.toFloat(),
                        0f..letters.lastIndex.toFloat(),
                        (letters.size - 2).coerceAtLeast(0),
                    )
                    setProgress { target ->
                        jump(letters[target.roundToInt().coerceIn(0, letters.lastIndex)])
                        true
                    }
                },
        )
        // Read while placing, so a moving list repositions the thumb without recomposing.
        fun thumbY(): Int = ((if (held != null) heldFraction else progress().coerceIn(0f, 1f)) * travel).roundToInt()
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(-ThumbInset.roundToPx(), thumbY()) }
                .graphicsLayer { this.alpha = alpha }
                .size(ThumbWidth, ThumbLength)
                .background(colors.onSurfaceVariant, Shapes.Pill),
        )
        // Only the thumb takes a touch, as Android's fast scroller: the rest of the lane stays the
        // list's. Grabbing it stops the list and shows its letter; moving it jumps.
        if (shown) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbY() - ((Sizes.TouchTarget - ThumbLength) / 2).roundToPx()) }
                    // A full touch target, wider than the lane it hangs from.
                    .wrapContentWidth(Alignment.End, unbounded = true)
                    .size(Sizes.TouchTarget)
                    .pointerInput(letters, travel) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            stop()
                            heldFraction = progress().coerceIn(0f, 1f)
                            held = current
                            // The gesture restarts when the letters change, so the hold ends with it either way.
                            try {
                                var y = heldFraction * travel + thumbHalf
                                drag(down.id) { change ->
                                    y += change.positionChange().y
                                    change.consume()
                                    pick(y)
                                }
                            } finally {
                                held = null
                            }
                        }
                    },
            )
        }
        // The letters can change under a hold for one frame before its gesture restarts.
        held?.let(letters::getOrNull)?.let { jump ->
            val style = MaterialTheme.typography.headlineSmall
            val letter = jump.letter
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        val y = thumbY() + thumbHalf - Bubble.toPx() / 2
                        IntOffset(-BubbleEndInset.roundToPx(), y.roundToInt())
                    }
                    // Wider than the lane it hangs from.
                    .wrapContentWidth(Alignment.End, unbounded = true)
                    .size(Bubble)
                    .background(colors.surfaceContainerHigh, Shapes.Circle),
                contentAlignment = Alignment.Center,
            ) {
                Text(letter, style = style, color = colors.onSurface, modifier = Modifier.inkCentre(letter, style))
            }
        }
    }
}

/** The scrubber for a list whose first [headerItems] items come before the letters' items. */
@Composable
internal fun BoxScope.LetterScrubber(
    letters: List<LetterJump>,
    state: LazyListState,
    headerItems: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    LetterScrubber(
        letters,
        scrolling = { state.isScrollInProgress },
        progress = {
            val info = state.layoutInfo
            val last = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            state.firstVisibleItemIndex.toFloat() / last
        },
        firstVisible = { state.firstVisibleItemIndex - headerItems },
        onJump = { scope.launch { state.scrollToItem(it.index + headerItems) } },
        onHold = { scope.launch { state.stopScroll() } },
        modifier = modifier.align(Alignment.TopEnd),
    )
}

/** The scrubber for a grid whose first [headerItems] items come before the letters' items. */
@Composable
internal fun BoxScope.LetterScrubber(
    letters: List<LetterJump>,
    state: LazyGridState,
    headerItems: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    LetterScrubber(
        letters,
        scrolling = { state.isScrollInProgress },
        progress = {
            val info = state.layoutInfo
            val last = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            state.firstVisibleItemIndex.toFloat() / last
        },
        firstVisible = { state.firstVisibleItemIndex - headerItems },
        onJump = { scope.launch { state.scrollToItem(it.index + headerItems) } },
        onHold = { scope.launch { state.stopScroll() } },
        modifier = modifier.align(Alignment.TopEnd),
    )
}

/** Where a letter starts in an A–Z list: [index] is the first item in [LibraryUiState.items] filed under [letter]. */
@Immutable
data class LetterJump(val letter: String, val index: Int)

/** Shorter lists scroll fine without a scrubber (about three phone screens of rows). */
const val SCRUBBER_MIN_ITEMS = 30

/**
 * The scrubber's letters for a list in A–Z order, from each item's sort name (Music Assistant's
 * `sort_name`, which already drops a leading "The"). Accents fold to their base letter; digits and
 * symbols file under "#". Lists shorter than [SCRUBBER_MIN_ITEMS] get no scrubber.
 */
fun letterJumps(sortNames: List<String>): List<LetterJump> {
    if (sortNames.size < SCRUBBER_MIN_ITEMS) return emptyList()
    val seen = HashSet<String>()
    val jumps = ArrayList<LetterJump>()
    sortNames.forEachIndexed { i, name ->
        val letter = indexLetter(name)
        if (seen.add(letter)) jumps += LetterJump(letter, i)
    }
    return if (jumps.size < 2) emptyList() else jumps
}

/** "Élodie" files under E, "4AM Radio" under #. */
internal fun indexLetter(name: String): String {
    val first = name.trimStart().firstOrNull() ?: return "#"
    val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first()
    return if (base.isLetter()) base.uppercaseChar().toString() else "#"
}
