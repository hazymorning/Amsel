package io.github.hazymorning.amsel.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.capHeight
import io.github.hazymorning.amsel.ui.theme.groupAfter
import kotlinx.coroutines.delay

/** How long a message stays: long enough to read a sentence, longer when it offers Retry. */
private const val SHORT_MS = 4_000L
private const val LONG_MS = 10_000L

/**
 * The app's one message: a plain sentence, with Retry when trying again can help. A bar on the
 * margins in the second surface tone, set apart by tone rather than shadow; the sentence and Retry
 * in ink, 16 inside its edges. A longer sentence wraps and the bar grows. The action stands beside
 * the sentence, centred on it, or under it at the end ([actionBeside]): a long word for Retry or
 * large type never squeezes the sentence into a narrow column of broken words.
 */
@Composable
fun MessageBar(message: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyLarge
    Surface(
        modifier
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = Keyline.Margin)
            .fillMaxWidth(),
        shape = Shapes.Tile,
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
    ) {
        Layout(
            content = {
                Text(
                    message,
                    style = style,
                    color = colors.onSurface,
                    modifier = Modifier.capToBaseline(style).trimStartBearing(message, style),
                )
                if (action != null) TextAction(action, onAction, color = colors.onSurface)
            },
            modifier = Modifier.heightIn(min = Sizes.FieldHeight).padding(Space.Sibling),
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val sentence = measurables[0]
            val button = measurables.getOrNull(1)?.measure(Constraints())
            if (button == null) {
                val text = sentence.measure(Constraints(maxWidth = width))
                val height = maxOf(text.height, constraints.minHeight)
                return@Layout layout(width, height) { text.placeRelative(0, (height - text.height) / 2) }
            }
            val room = width - Space.Sibling.roundToPx() - button.width
            val cap = capHeight(style).roundToPx()
            val gap = groupAfter(style).roundToPx()
            val beside = actionBeside(
                bar = width + Space.Sibling.roundToPx() * 2,
                room = room,
                longestWord = sentence.minIntrinsicWidth(constraints.maxHeight),
                linesBeside = sentence.minIntrinsicHeight(room),
                linesUnder = sentence.minIntrinsicHeight(width) + gap + cap,
            )
            val text = sentence.measure(Constraints(maxWidth = if (beside) room else width))
            // The action's layout is a line's box; it is placed by its capitals, as the sentence is.
            val capTop = button[FirstBaseline].takeIf { it != AlignmentLine.Unspecified }?.minus(cap)
                ?: ((button.height - cap) / 2)
            if (beside) {
                val height = maxOf(text.height, cap, constraints.minHeight)
                layout(width, height) {
                    text.placeRelative(0, (height - text.height) / 2)
                    button.placeRelative(width - button.width, (height - cap) / 2 - capTop)
                }
            } else {
                layout(width, text.height + gap + cap) {
                    text.placeRelative(0, 0)
                    button.placeRelative(width - button.width, text.height + gap - capTop)
                }
            }
        }
    }
}

/**
 * Whether a message's action stands beside its sentence: while the sentence keeps at least half of
 * the [bar] beside it ([room]), none of its words has to break ([longestWord]), and its lines there
 * make the bar no taller than its lines at full width with the action on a row of its own under
 * them ([linesBeside], [linesUnder]): beside may cost the sentence a line, not two.
 */
internal fun actionBeside(bar: Int, room: Int, longestWord: Int, linesBeside: Int, linesUnder: Int): Boolean =
    room * 2 >= bar && longestWord <= room && linesBeside <= linesUnder

/**
 * Shows [state]'s messages one at a time as [MessageBar]s, fading between them. A message can be
 * swiped away to either side. TalkBack reads each as it appears and can dismiss it; with TalkBack
 * on, a message stays as long as the person's accessibility settings ask.
 */
@Composable
fun MessageHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val current = state.currentSnackbarData
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(current) {
        val data = current ?: return@LaunchedEffect
        val base = when (data.visuals.duration) {
            SnackbarDuration.Indefinite -> return@LaunchedEffect
            SnackbarDuration.Long -> LONG_MS
            SnackbarDuration.Short -> SHORT_MS
        }
        val hasAction = data.visuals.actionLabel != null
        val shown = accessibility?.calculateRecommendedTimeoutMillis(
            base,
            containsText = true,
            containsControls = hasAction,
        ) ?: base
        delay(shown)
        data.dismiss()
    }
    Crossfade(current, modifier, animationSpec = Motion.effects(), label = "message") { data ->
        if (data == null) return@Crossfade
        Box(
            Modifier
                .swipeAway(data::dismiss)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    dismiss {
                        data.dismiss()
                        true
                    }
                },
        ) {
            MessageBar(data.visuals.message, action = data.visuals.actionLabel, onAction = data::performAction)
        }
    }
}
