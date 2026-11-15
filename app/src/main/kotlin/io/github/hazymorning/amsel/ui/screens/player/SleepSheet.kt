package io.github.hazymorning.amsel.ui.screens.player

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.SleepMode
import io.github.hazymorning.amsel.app.SleepTimerState
import io.github.hazymorning.amsel.ui.components.ChoiceRow
import io.github.hazymorning.amsel.ui.components.InkGlyph
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.formatDuration
import io.github.hazymorning.amsel.ui.components.inkStart
import io.github.hazymorning.amsel.ui.components.sheetEnd
import io.github.hazymorning.amsel.ui.components.textInk
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.theme.DISABLED_ALPHA
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.capHeight
import io.github.hazymorning.amsel.ui.theme.rememberPress
import io.github.hazymorning.amsel.ui.theme.secondLine
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The sleep timer of [speaker]: under the title one line that is always there, its length and what
 * is left while it runs; one slider from off through five to 120 minutes in fives; Off and the
 * presets; and for a book or an episode the end of the chapter or the episode ([endLabel]). Nothing
 * moves when a timer starts, stops or changes: Off keeps its place, dimmed while none runs.
 */
@Composable
fun SleepSheetContent(
    speaker: String,
    timer: SleepTimerState?,
    @StringRes endLabel: Int?,
    onSet: (SleepMode) -> Unit,
    onOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode = timer?.mode
    var dragging by remember { mutableStateOf<Int?>(null) }
    val minutes = dragging ?: (mode as? SleepMode.Minutes)?.minutes ?: 0
    // A timer set elsewhere has no length here: only what is left of it is known.
    val length = when {
        dragging != null -> minutesLabel(minutes)
        mode == SleepMode.EndOfItem && endLabel != null -> stringResource(endLabel)
        mode is SleepMode.Minutes -> minutesLabel(mode.minutes)
        timer != null -> null
        else -> stringResource(R.string.sleep_off)
    }
    // What is left gives way to the length under the finger: it is about to change.
    val left = timer?.endsAtMs?.takeIf { dragging == null }?.let { ends ->
        stringResource(R.string.sleep_left, formatDuration(rememberLeft(ends).value))
    }
    val end = if (endLabel != null) sheetEnd(RowLead.None, twoLines = false) else Space.SheetEdge
    Column(modifier.fillMaxWidth().padding(bottom = end)) {
        SheetHeader(stringResource(R.string.sleep_timer), name = speaker)
        SleepLine(length, left)
        Spacer(Modifier.height(Space.Group))
        LineSlider(
            fraction = { minutes / MAX_MINUTES.toFloat() },
            semanticValue = minutes / MAX_MINUTES.toFloat(),
            label = stringResource(R.string.sleep_length),
            stateDescription = length ?: stringResource(R.string.sleep_timer),
            onChange = { dragging = snapped(it) },
            onChangeFinished = {
                val chosen = snapped(it)
                dragging = null
                if (chosen == 0) onOff() else onSet(SleepMode.Minutes(chosen))
            },
            modifier = Modifier.padding(horizontal = Keyline.Margin),
        )
        Spacer(Modifier.height(Space.Group))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Keyline.Margin).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Space.S2),
        ) {
            Preset(null, selected = false, onClick = onOff, Modifier.weight(1f), enabled = timer != null)
            PRESETS.forEach { preset ->
                Preset(
                    preset,
                    selected = (mode as? SleepMode.Minutes)?.minutes == preset,
                    onClick = { onSet(SleepMode.Minutes(preset)) },
                    Modifier.weight(1f),
                )
            }
        }
        if (endLabel != null) {
            Spacer(Modifier.height(Space.S3))
            ChoiceRow(stringResource(endLabel), selected = mode == SleepMode.EndOfItem, onClick = {
                onSet(SleepMode.EndOfItem)
            })
        }
    }
}

/**
 * The line under the title, always there, so nothing under it moves: how long the timer is set for
 * ("Off" while none runs) and, while one runs, what is [left] of it, in moss. Where one line cannot
 * hold both, what is left stands alone: the slider and the ticked row still say the length.
 */
@Composable
private fun SleepLine(length: String?, left: String?) {
    val colors = MaterialTheme.colorScheme
    // Figures of one width, so the line holds still while it counts down.
    val style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    val above = with(LocalDensity.current) { secondLine(style) - capHeight(style) }
    val line = listOfNotNull(length, left).joinToString(DOT_SEPARATOR)
    Words(
        line,
        style,
        color = if (left != null) colors.tertiary else colors.onSurfaceVariant,
        shorter = left,
        modifier = Modifier
            .padding(start = Keyline.Margin, top = above, end = Keyline.Margin)
            .textInk(style)
            .inkStart(line, style),
    )
}

/** One preset; [minutes] null is Off, shown as a cross, dimmed while there is nothing to turn off. */
@Composable
private fun Preset(
    minutes: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val label = if (minutes == null) stringResource(R.string.sleep_turn_off) else minutesLabel(minutes)
    val ink = if (selected) colors.surface else colors.onSurface
    Box(
        modifier
            .heightIn(min = Sizes.TouchTarget)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(Shapes.Pill)
            .background(if (selected) colors.onSurface else colors.surfaceContainerHigh)
            .selectable(
                selected,
                interactionSource = null,
                indication = rememberPress(Shapes.Pill),
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (minutes == null) {
            InkGlyph(R.drawable.ic_close, tint = ink, size = Sizes.IconSmall)
        } else {
            Text(
                minutes.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = ink,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun minutesLabel(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.sleep_off) else stringResource(R.string.sleep_minutes, minutes)

/** Seconds until [endsAtMs], ticking once a second. */
@Composable
internal fun rememberLeft(endsAtMs: Long): State<Int> {
    val left = remember(endsAtMs) { mutableIntStateOf(secondsUntil(endsAtMs)) }
    LaunchedEffect(endsAtMs) {
        while (left.intValue > 0) {
            delay(TICK_MS - System.currentTimeMillis() % TICK_MS)
            left.intValue = secondsUntil(endsAtMs)
        }
    }
    return left
}

private fun secondsUntil(endsAtMs: Long): Int =
    ((endsAtMs - System.currentTimeMillis() + TICK_MS - 1) / TICK_MS).toInt().coerceAtLeast(0)

private fun snapped(fraction: Float): Int = (fraction * STEPS).roundToInt() * STEP_MINUTES

private const val TICK_MS = 1000L
private const val STEP_MINUTES = 5
private const val MAX_MINUTES = 120
private const val STEPS = MAX_MINUTES / STEP_MINUTES
private val PRESETS = listOf(15, 30, 45, 60)

/** The countdown beside the moon on Now Playing, or null when no timer runs. */
@Composable
internal fun sleepLeftText(timer: SleepTimerState?): String? {
    val ends = timer?.endsAtMs ?: return null
    val left by rememberLeft(ends)
    return if (left > 0) formatDuration(left) else null
}
