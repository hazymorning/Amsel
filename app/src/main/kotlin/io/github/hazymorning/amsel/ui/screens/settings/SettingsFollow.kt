package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.inkStart
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.rememberPress

/** A speaker following the music may pick. */
@Immutable
data class FollowSpeaker(val id: String, val name: String)

/**
 * Under Follow the music while it is on: the speakers it may pick, two to a line, as small plain
 * buttons, their names on the start as every setting's are. One it follows is ink, as a switch that
 * is on; one left out lies on the second tone. Turning following on takes in every speaker; leaving
 * all but one out follows that one alone. The last one in stays in.
 */
@Composable
internal fun FollowSpeakers(
    speakers: List<FollowSpeaker>,
    skipped: Set<String>,
    shape: Shape,
    onChange: (Set<String>) -> Unit,
) {
    val followed = speakers.count { it.id !in skipped }
    Column(
        Modifier
            .padding(horizontal = BlockEdge)
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(RowInset),
        verticalArrangement = Arrangement.spacedBy(Space.S2),
    ) {
        speakers.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.S2)) {
                pair.forEach { speaker ->
                    val on = speaker.id !in skipped
                    FollowChip(speaker.name, on, Modifier.weight(1f)) {
                        if (!on) {
                            onChange(skipped - speaker.id)
                        } else if (followed > 1) {
                            onChange(skipped + speaker.id)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FollowChip(name: String, on: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.titleSmall
    val haptics = LocalHapticFeedback.current
    val fill by animateColorAsState(if (on) colors.onSurface else colors.surfaceContainerHigh, Motion.effects())
    val ink by animateColorAsState(if (on) colors.surface else colors.onSurface, Motion.effects())
    Box(
        modifier
            .heightIn(min = Sizes.ChipHeight)
            .clip(Shapes.Pill)
            .background(fill)
            .toggleable(
                value = on,
                interactionSource = null,
                indication = rememberPress(Shapes.Pill),
                role = Role.Checkbox,
                onValueChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onToggle()
                },
            )
            .padding(horizontal = ChipWords),
        contentAlignment = Alignment.CenterStart,
    ) {
        Words(name, style, color = ink, modifier = Modifier.inkStart(name, style))
    }
}

/** A chip's name stands this far in from its round end, as the pills' words do. */
private val ChipWords = 16.dp
