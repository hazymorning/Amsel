package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.secondLine
import io.github.hazymorning.amsel.ui.theme.Sizes

@Composable
fun MediaRow(
    item: MediaUi,
    onClick: () -> Unit,
    /** Null where a long press does something else (moving a pin in Edit Home). */
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
