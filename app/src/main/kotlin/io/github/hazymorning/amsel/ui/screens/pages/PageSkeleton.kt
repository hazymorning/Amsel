package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.RowPlaceholder
import io.github.hazymorning.amsel.ui.components.TilePlaceholder
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space

/*
 * Skeleton blocks for a page's very first load: the shared placeholders (ui/components), so each
 * is exactly as tall as what replaces it at every font scale and nothing moves when data arrives.
 */

/** Rows drawn while a page's tracks load: about a screenful, no more. */
private const val SKELETON_ROWS = 6

/** A screenful of track row blocks, each with the rows' [lead] (none: words only) and line count. */
internal fun LazyListScope.trackRowBlocks(lead: RowLead, withSubtitle: Boolean, count: Int = SKELETON_ROWS) {
    items(count, key = { "track_block_$it" }, contentType = { PageSlot.Track }) {
        RowPlaceholder(it, lead = lead, lines = if (withSubtitle) 2 else 1)
    }
}

/** One row of [columns] tile blocks, laid out like the album grid. */
@Composable
internal fun TileRowBlock(columns: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Keyline.Margin),
        horizontalArrangement = Arrangement.spacedBy(Space.Tiles),
    ) {
        repeat(columns) { TilePlaceholder(it, Modifier.weight(1f)) }
    }
}
