package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.hazymorning.amsel.ui.components.ListeningTile
import io.github.hazymorning.amsel.ui.components.ShelfRow
import io.github.hazymorning.amsel.ui.components.TilePlaceholder
import io.github.hazymorning.amsel.ui.components.leftText
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space

/**
 * Continue listening as big covers with no card: each tile resumes where the person stopped; a
 * long press opens its actions, which lead to its page.
 */
@Composable
internal fun ListeningShelf(
    items: List<MediaUi>,
    onResume: (MediaUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val width = bigTileWidth()
    ShelfRow(items, key = { it.key }, modifier) { item ->
        ListeningTile(
            item,
            onPlay = { onResume(item) },
            onMore = { onMore(item) },
            width = width,
            remaining = item.remainingSec?.let { leftText(it) },
        )
    }
}

/** The tiles' twins while Continue listening loads for the first time. */
@Composable
internal fun ListeningSkeleton(modifier: Modifier = Modifier) {
    val width = bigTileWidth()
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState(), enabled = false)
            .padding(horizontal = Keyline.Margin),
        horizontalArrangement = Arrangement.spacedBy(Space.Tiles),
    ) {
        repeat(SKELETON_CARDS) { TilePlaceholder(it, Modifier.width(width), twoLines = false) }
    }
}

/** Continue listening in any other layout: what is left joins each item's second line. */
@Composable
internal fun withTimeLeft(items: List<MediaUi>): List<MediaUi> = items.map { item ->
    val left = item.remainingSec?.let { leftText(it) } ?: return@map item
    item.copy(subtitle = listOfNotNull(item.subtitle, left).joinToString(DOT_SEPARATOR))
}

private const val SKELETON_CARDS = 2
