package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.github.hazymorning.amsel.ui.components.MediaTile
import io.github.hazymorning.amsel.ui.components.SectionHeader
import io.github.hazymorning.amsel.ui.components.ShelfRow
import io.github.hazymorning.amsel.ui.components.shelfTileWidth
import io.github.hazymorning.amsel.ui.model.MediaUi

/**
 * A page's last section, one a page: [title] over a sideways shelf of [items], nothing at all when
 * there are none. [above] is how far the last ink before it sits inside its layout; [twoLines]
 * false leaves out the tiles' second line (artists).
 */
internal fun LazyListScope.pageShelf(
    title: String,
    items: List<MediaUi>,
    above: Dp,
    onOpen: (MediaUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    twoLines: Boolean = true,
) {
    if (items.isEmpty()) return
    item(key = "shelf_header", contentType = PageSlot.Header) { SectionHeader(title, above = above) }
    item(key = "shelf", contentType = PageSlot.Shelf) {
        val width = shelfTileWidth()
        ShelfRow(items, key = { it.key }) { item ->
            MediaTile(
                item,
                onClick = { onOpen(item) },
                onMore = { onMore(item) },
                modifier = Modifier.width(width),
                twoLines = twoLines,
            )
        }
    }
}
