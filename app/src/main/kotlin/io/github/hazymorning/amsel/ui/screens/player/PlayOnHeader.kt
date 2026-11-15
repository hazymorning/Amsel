package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.QuietPill
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space

/**
 * The heading with Group on its line, at the end, on the margin: it names the group that exists
 * and the [members] playing along in it ("Group +2"), or offers to make one. Null [members]: no
 * group, and the speaker in use cannot lead one. Where the line is too short for the heading and
 * Group, as at a large font, Group stands under the heading, on the margin there too.
 */
@Composable
internal fun PlayOnHeader(members: Int?, onGrouping: () -> Unit) {
    Layout(
        content = {
            SheetHeader(stringResource(R.string.player_play_on))
            if (members != null) {
                val group = stringResource(if (members > 0) R.string.group_count else R.string.group, members)
                QuietPill(R.drawable.ic_group, group, onGrouping, height = Sizes.SpeakerPill)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val margin = Keyline.Margin.roundToPx()
        val heading = measurables.first()
        val group = measurables.getOrNull(1)
            ?.measure(loose.copy(maxWidth = (constraints.maxWidth - 2 * margin).coerceAtLeast(0)))
        val oneLine = group == null ||
            heading.maxIntrinsicWidth(constraints.maxHeight) + Space.S3.roundToPx() + group.width <=
            constraints.maxWidth
        val words = heading.measure(loose)
        if (group == null || oneLine) {
            val height = maxOf(words.height, group?.height ?: 0)
            layout(constraints.maxWidth, height) {
                words.placeRelative(0, (height - words.height) / 2)
                group?.placeRelative(constraints.maxWidth - margin - group.width, (height - group.height) / 2)
            }
        } else {
            val top = words.height + Space.S3.roundToPx()
            layout(constraints.maxWidth, top + group.height) {
                words.placeRelative(0, 0)
                group.placeRelative(margin, top)
            }
        }
    }
}
