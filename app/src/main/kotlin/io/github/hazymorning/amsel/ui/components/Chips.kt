package io.github.hazymorning.amsel.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.LocalReducedMotion
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.PILL_ALPHA
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.rememberPress
import kotlin.math.roundToInt

private val ChipReach = (Sizes.TouchTarget - Sizes.ChipHeight) / 2

private val ChipPadding = 16.dp

/**
 * The fill alone marks the selection: an outline or a tick would only repeat it. The layout is the
 * visible chip; the 48 dp target overhangs it vertically.
 */
@Composable
fun ChoiceChip(selected: Boolean, onClick: () -> Unit, label: String, role: Role, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.titleSmall
    val fill by animateColorAsState(
        if (selected) colors.surfaceContainerHigh else colors.surfaceContainerHigh.copy(alpha = 0f),
        Motion.effects(),
        label = "chip",
    )
    val text by animateColorAsState(if (selected) colors.onSurface else colors.onSurfaceVariant, Motion.effects())
    val haptics = LocalHapticFeedback.current
    val touch = remember { MutableInteractionSource() }
    Box(
        modifier
            .overhang(top = ChipReach, bottom = ChipReach)
            .selectable(
                selected,
                interactionSource = touch,
                indication = null,
                role = role,
                onClick = {
                    if (!selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onClick()
                },
            )
            .padding(vertical = ChipReach)
            .indication(touch, rememberPress(Shapes.Pill))
            .heightIn(min = Sizes.ChipHeight)
            .clip(Shapes.Pill)
            .background(fill)
            .padding(horizontal = ChipPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = style,
            color = text,
            maxLines = 1,
            modifier = Modifier.trimSideBearings(label, style),
        )
    }
}

/** Shared by rows that show the same choice (a header repeated on each page), so they scroll as one. */
@Stable
class ChipRowState internal constructor(internal val scroll: ScrollState) {
    internal var shown = -1
}

@Composable
fun rememberChipRowState(): ChipRowState {
    val scroll = rememberScrollState()
    return remember(scroll) { ChipRowState(scroll) }
}

/**
 * The first chip's edge, not its label, sits on the margin, so a selected fill never reaches past it.
 * Whenever [selected] changes, by a tap or by a swipe between pages, the row centres that chip.
 */
@Composable
fun <T> ChoiceChipRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    /** [Role.Tab] when the choice switches the content below, [Role.RadioButton] for a setting's values. */
    role: Role,
    modifier: Modifier = Modifier,
    state: ChipRowState = rememberChipRowState(),
) {
    val scroll = state.scroll
    // Plain map, not state: read only in the effect below, never in composition.
    val middles = remember { mutableMapOf<Int, Int>() }
    val chosen = options.indexOf(selected)
    val still = LocalReducedMotion.current
    LaunchedEffect(chosen) {
        // Wait a frame, so onPlaced has recorded the chips' positions.
        withFrameNanos {}
        val middle = middles[chosen] ?: return@LaunchedEffect
        val target = (middle - scroll.viewportSize / 2).coerceIn(0, scroll.maxValue)
        // Jump on first display; animate later changes unless motion is reduced.
        if (state.shown >= 0 && !still) scroll.animateScrollTo(target, Motion.spatial()) else scroll.scrollTo(target)
        state.shown = chosen
    }
    Row(
        modifier
            .fillMaxWidth()
            .fadingEdges(scroll)
            .horizontalScroll(scroll)
            .selectableGroup()
            .padding(PaddingValues(horizontal = Keyline.Margin)),
        horizontalArrangement = Arrangement.spacedBy(Space.Part),
    ) {
        options.forEachIndexed { index, option ->
            ChoiceChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = label(option),
                role = role,
                modifier = Modifier.onPlaced {
                    middles[index] =
                        (it.positionInParent().x + it.size.width / 2f).roundToInt()
                },
            )
        }
    }
}

/** [contentDescription], if given, names the setting and its value ("Show as: Grid"). */
@Composable
fun DropdownChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyLarge
    val described = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    val touch = remember { MutableInteractionSource() }
    Row(
        modifier
            .overhang(top = DropdownReach, bottom = DropdownReach)
            .clickable(touch, indication = null, role = Role.DropdownList, onClick = onClick)
            .then(described)
            .padding(vertical = DropdownReach)
            .indication(touch, rememberPress(Shapes.Pill))
            .heightIn(min = DropdownHeight)
            .clip(Shapes.Pill)
            .background(colors.onSurface.copy(alpha = PILL_ALPHA))
            .padding(start = ChipPadding, top = Space.S2, end = DropdownEnd, bottom = Space.S2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Words(
            label,
            style,
            color = colors.onSurface,
            maxLines = DROPDOWN_LINES,
            modifier = Modifier.weight(1f, fill = false).capToBaseline(style).trimStartBearing(label, style),
        )
        Spacer(Modifier.width(Space.Part))
        AlignedGlyph(R.drawable.ic_dropdown, tint = colors.onSurfaceVariant, size = wordCaret(style))
    }
}

private val DropdownHeight = Sizes.PillHeight
private val DropdownReach = (Sizes.ButtonHeight - DropdownHeight) / 2

private val DropdownEnd = 12.dp

private const val DROPDOWN_LINES = 3
