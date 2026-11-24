package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.Artwork
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.trimStartBearing
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.rememberPress

// Minimum title width for two columns, in ems of the title's font size (about nine letters a
// line): a narrow phone keeps two columns, only large text drops to one.
private const val TITLE_MIN_EMS = 4.5f

internal val CardGap = Space.S2

private val CardPadding = Space.S3

// A custom Layout because a lazy grid cannot be measured inside a lazy column. Cards are measured
// at the column width, never by intrinsics, which Words cannot report correctly.
@Composable
internal fun CardGrid(
    count: Int,
    modifier: Modifier = Modifier,
    card: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.padding(horizontal = Keyline.Margin)) {
        val columns = cardColumns(maxWidth)
        val cell = (maxWidth - CardGap * (columns - 1)) / columns
        Layout({ repeat(count) { card(it, Modifier) } }) { measurables, constraints ->
            val width = cell.roundToPx()
            val gapX = CardGap.roundToPx()
            val gapY = CardGap.roundToPx()
            val rows = measurables.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }.chunked(columns)
            val heights = rows.map { row -> row.maxOf { it.height } }
            val height = heights.sum() + gapY * (rows.size - 1).coerceAtLeast(0)
            layout(constraints.maxWidth, height) {
                var y = 0
                rows.forEachIndexed { r, row ->
                    row.forEachIndexed { c, placeable -> placeable.placeRelative(c * (width + gapX), y) }
                    y += heights[r] + gapY
                }
            }
        }
    }
}

@Composable
internal fun cardColumns(width: Dp): Int =
    cardColumns(width, with(LocalDensity.current) { MaterialTheme.typography.titleSmall.fontSize.toDp() })

internal fun cardColumns(width: Dp, titleEm: Dp): Int {
    val titleRoom = (width - CardGap) / 2 - Sizes.RowArt - CardPadding * 2
    return if (titleRoom >= titleEm * TITLE_MIN_EMS) 2 else 1
}

@Composable
internal fun HomeCard(item: MediaUi, onClick: () -> Unit, onMore: (() -> Unit)?, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.titleSmall
    Row(
        modifier
            .heightIn(min = Sizes.RowArt)
            .clip(Shapes.Thumb)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(
                interactionSource = null,
                indication = rememberPress(shape = null),
                role = Role.Button,
                onClick = onClick,
                onLongClickLabel = onMore?.let { stringResource(R.string.more_options_for, item.title) },
                onLongClick = onMore,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item.art, Modifier.size(Sizes.RowArt), item.kind, shape = RectangleShape, name = item.title)
        Words(
            item.title,
            style,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            modifier = Modifier.weight(1f).padding(horizontal = CardPadding).trimStartBearing(item.title, style),
        )
    }
}
