package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.secondLine
import io.github.hazymorning.amsel.ui.theme.Sizes

/**
 * A tap opens the item, where every action is visible; a long press is the shortcut to them.
 * [playing] marks what plays now: a radio station that is on. Its second line is the server's or
 * what the item is ([secondLine]); a list in which no row has one passes null ([twoLines]).
 */
@Composable
fun MediaRow(
    item: MediaUi,
    onClick: () -> Unit,
    /** The long press; null where a long press does something else (moving a pin in Edit Home). */
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    subtitle: String? = item.secondLine(),
) {
    ListRow(
        title = item.title,
        subtitle = subtitle,
        modifier = modifier,
        lead = RowLead.Cover,
        playing = playing,
        onClick = onClick,
        onLongClick = onMore,
        leading = { Artwork(item.art, Modifier.size(Sizes.RowArt), item.kind, name = item.title) },
        mark = downloadMarkGlyph(item.key),
    )
}
