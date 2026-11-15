package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.QuietGlyph
import io.github.hazymorning.amsel.ui.components.QuietPill
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space

/**
 * The heading with the group's controls on its line, at the end. Group stands on the margin: it
 * names the group that exists and the [members] playing along in it ("Group +2"), or offers to
 * make one. Null [members]: no group, and the speaker in use cannot lead one. While speakers are
 * [grouped], Ungroup stands beside it as a glyph alone and lets them all go, so Group keeps its
 * place. Where the line is too short for the heading and both, as at a large font, they stand
 * under the heading, Group on the margin there too.
 */
@Composable
internal fun PlayOnHeader(members: Int?, grouped: Boolean, onGrouping: () -> Unit, onUngroup: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Layout(
        content = {
            SheetHeader(stringResource(R.string.player_play_on))
            if (members != null) {
                val group = stringResource(if (members > 0) R.string.group_count else R.string.group, members)
                QuietPill(
                    R.drawable.ic_group,
                    group,
                    onGrouping,
                    Modifier.layoutId(Control.Group),
                    height = Sizes.SpeakerPill,
                )
            }
            if (grouped) {
                QuietGlyph(
                    R.drawable.ic_ungroup,
                    stringResource(R.string.ungroup_all),
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onUngroup()
                    },
                    modifier = Modifier.layoutId(Control.Ungroup),
                    height = Sizes.SpeakerPill,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val margin = Keyline.Margin.roundToPx()
        val between = Space.Part.roundToPx()
        val ungroup = measurables.firstOrNull { it.layoutId == Control.Ungroup }?.measure(loose)
        val room = constraints.maxWidth - 2 * margin - (ungroup?.let { it.width + between } ?: 0)
        val group = measurables.firstOrNull { it.layoutId == Control.Group }
            ?.measure(loose.copy(maxWidth = room.coerceAtLeast(0)))
        val controls = listOfNotNull(group, ungroup)
        val width = controls.sumOf { it.width } + between * (controls.size - 1).coerceAtLeast(0)
        val heading = measurables.first()
        val oneLine = controls.isEmpty() ||
            heading.maxIntrinsicWidth(constraints.maxHeight) + Space.S3.roundToPx() + width <= constraints.maxWidth
        val words = heading.measure(loose)
        val line = controls.maxOfOrNull { it.height } ?: 0
        if (oneLine) {
            val height = maxOf(words.height, line)
            layout(constraints.maxWidth, height) {
                words.placeRelative(0, (height - words.height) / 2)
                var end = constraints.maxWidth - margin
                controls.forEach {
                    it.placeRelative(end - it.width, (height - it.height) / 2)
                    end -= it.width + between
                }
            }
        } else {
            val top = words.height + Space.S3.roundToPx()
            layout(constraints.maxWidth, top + line) {
                words.placeRelative(0, 0)
                var start = margin
                controls.forEach {
                    it.placeRelative(start, top + (line - it.height) / 2)
                    start += it.width + between
                }
            }
        }
    }
}

private enum class Control { Group, Ungroup }
