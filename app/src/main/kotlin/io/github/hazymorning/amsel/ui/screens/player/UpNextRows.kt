package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.AlignedGlyph
import io.github.hazymorning.amsel.ui.components.Artwork
import io.github.hazymorning.amsel.ui.components.DragHandle
import io.github.hazymorning.amsel.ui.components.ListRow
import io.github.hazymorning.amsel.ui.components.ReorderState
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.RowPlaceholder
import io.github.hazymorning.amsel.ui.components.reorderHandle
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.QueueItemUi
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space

/**
 * [played] songs are quiet: secondary title, dimmed cover. Only what comes next has a handle: the
 * current song carries the ring, and songs already played stay where they were. A long press opens
 * a song's actions ([onMore]; null where it has none). What the DJ says between two songs stands
 * under the server's name for it, over "DJ".
 */
@Composable
internal fun QueueRow(
    item: QueueItemUi,
    played: Boolean,
    lifted: Boolean,
    reorder: ReorderState?,
    actions: List<CustomAccessibilityAction>,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    ListRow(
        title = item.track.title,
        subtitle = if (item.track.djBreak) stringResource(R.string.dj) else item.track.artist,
        modifier = Modifier
            .background(colors.surface)
            .semantics { customActions = actions },
        lead = RowLead.Cover,
        playing = item.isCurrent,
        quiet = played,
        band = if (lifted) colors.surfaceContainerLow else null,
        onClick = onClick,
        onLongClick = onMore,
        leading = {
            Artwork(
                item.track.art,
                Modifier.size(Sizes.RowArt),
                MediaKind.Track,
                dimmed = played,
                name = item.track.album ?: item.track.title,
            )
        },
        trailing = reorder?.let { { DragHandle(Modifier.reorderHandle(it, item.id)) } },
    )
}

/** TalkBack's way to move and remove, since dragging and swiping are touch gestures. */
@Composable
internal fun rowActions(
    index: Int,
    last: Int,
    firstEditable: Int,
    item: QueueItemUi,
    move: (Int, Int) -> Unit,
    remove: (QueueItemUi) -> Unit,
): List<CustomAccessibilityAction> {
    if (index < firstEditable) return emptyList()
    val up = stringResource(R.string.move_up)
    val down = stringResource(R.string.move_down)
    val removeLabel = stringResource(R.string.player_action_remove_from_up_next)
    return buildList {
        if (index > firstEditable) {
            add(
                CustomAccessibilityAction(up) {
                    move(index, index - 1)
                    true
                },
            )
        }
        if (index < last) {
            add(
                CustomAccessibilityAction(down) {
                    move(index, index + 1)
                    true
                },
            )
        }
        add(
            CustomAccessibilityAction(removeLabel) {
                remove(item)
                true
            },
        )
    }
}

@Composable
internal fun RemoveBackground(direction: SwipeToDismissBoxValue) {
    val side = if (direction == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Keyline.Margin - Space.BandInset)
            .clip(Shapes.Band)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = Space.BandInset),
        horizontalArrangement = side,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlignedGlyph(R.drawable.ic_remove, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun UpNextSkeleton(modifier: Modifier = Modifier) {
    val loading = stringResource(R.string.player_up_next_loading)
    Column(modifier.clearAndSetSemantics { contentDescription = loading }) {
        repeat(SKELETON_ROWS) { RowPlaceholder(it) }
    }
}

private const val SKELETON_ROWS = 6
