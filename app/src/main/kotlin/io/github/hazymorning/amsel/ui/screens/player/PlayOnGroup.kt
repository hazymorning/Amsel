package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.InkGlyph
import io.github.hazymorning.amsel.ui.components.ListRow
import io.github.hazymorning.amsel.ui.components.PrimaryButton
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.rowInkBottom
import io.github.hazymorning.amsel.ui.model.displayName
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space

/**
 * The speakers that can play along with the group's leader, each with a round check: ticked ones
 * play in step with it. Done applies what changed; Back leaves it as it was.
 */
@Composable
internal fun GroupList(
    state: PlayOnUiState,
    onDone: (leaderId: String, add: List<String>, remove: List<String>) -> Unit,
) {
    val leader = state.leader ?: return
    // What Done changes is counted from the group as it was when this list opened.
    val before = rememberSaveable(leader.id) { state.group.filter { it.member }.map { it.id } }
    var chosen by rememberSaveable(leader.id) { mutableStateOf(before) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Space.SheetEdge)) {
        SheetHeader(stringResource(R.string.group_with, leader.displayName()))
        Spacer(Modifier.height(Space.HeaderToContent))
        state.group.forEach { choice ->
            val ticked = choice.id in chosen
            val name = if (choice.isPhone) stringResource(R.string.this_phone) else choice.name
            ListRow(
                title = name,
                lead = RowLead.Glyph,
                role = Role.Checkbox,
                modifier = Modifier.semantics { toggleableState = ToggleableState(ticked) },
                textMaxWidth = SpeakerTextMax,
                onClick = { chosen = if (ticked) chosen - choice.id else chosen + choice.id },
                leading = {
                    val colors = MaterialTheme.colorScheme
                    InkGlyph(
                        if (choice.isPhone) R.drawable.ic_phone else R.drawable.ic_speaker,
                        tint = if (ticked) colors.onSurface else colors.onSurfaceVariant,
                    )
                },
                trailing = { RoundCheck(ticked) },
            )
        }
        // Done stands a group's distance under the last speaker's name, as Group does in the list.
        Spacer(Modifier.height((Space.BeforeList - rowInkBottom(RowLead.Glyph, twoLines = false)).coerceAtLeast(0.dp)))
        PrimaryButton(
            stringResource(R.string.home_edit_done),
            onClick = { onDone(leader.id, chosen - before.toSet(), before - chosen.toSet()) },
            modifier = Modifier.padding(horizontal = Keyline.Margin).fillMaxWidth(),
        )
    }
}

/** A tick in a filled circle when on, an empty ring when off. */
@Composable
private fun RoundCheck(on: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(CheckSize)
            .clip(Shapes.Circle)
            .background(if (on) colors.onSurface else colors.surface.copy(alpha = 0f))
            .border(CheckRing, if (on) colors.onSurface else colors.outline, Shapes.Circle),
        contentAlignment = Alignment.Center,
    ) {
        if (on) InkGlyph(R.drawable.ic_check, tint = colors.surface, size = Sizes.IconSmall)
    }
}

private val CheckSize = 24.dp
private val CheckRing = 1.5.dp
