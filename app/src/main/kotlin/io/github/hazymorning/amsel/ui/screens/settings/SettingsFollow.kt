package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import io.github.hazymorning.amsel.ui.components.ChoiceChip
import io.github.hazymorning.amsel.ui.theme.Space

/** A speaker following the music may pick. */
@Immutable
data class FollowSpeaker(val id: String, val name: String)

/**
 * Under Follow the music while it is on: the speakers it may pick, two to a line, as small plain
 * chips, those it follows on Surface 2, those left out their name alone. Turning following on takes
 * in every speaker; leaving all but one out follows that one alone. The last one in stays in.
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
                    ChoiceChip(
                        selected = on,
                        onClick = {
                            if (!on) {
                                onChange(skipped - speaker.id)
                            } else if (followed > 1) {
                                onChange(skipped + speaker.id)
                            }
                        },
                        label = speaker.name,
                        role = Role.Checkbox,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
