package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.Artwork
import io.github.hazymorning.amsel.ui.components.ListRow
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.inkStart
import io.github.hazymorning.amsel.ui.components.sheetEnd
import io.github.hazymorning.amsel.ui.components.textInk
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space

/** "Add to playlist": the playlists you can change, one tap to add; [playlists] is null while they are read. */
@Composable
fun PlaylistPicker(playlists: List<MediaUi>?, onPick: (MediaUi) -> Unit, modifier: Modifier = Modifier) {
    val end = if (playlists.isNullOrEmpty()) Space.SheetEdge else sheetEnd(RowLead.Cover, twoLines = true)
    Column(modifier.fillMaxWidth().padding(bottom = end)) {
        SheetHeader(stringResource(R.string.add_to_playlist))
        if (playlists == null) return@Column
        if (playlists.isEmpty()) {
            val style = MaterialTheme.typography.bodyLarge
            val text = stringResource(R.string.no_editable_playlists)
            Spacer(Modifier.height(Space.Group))
            Text(
                text,
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Keyline.Margin)
                    .widthIn(max = Sizes.Measure)
                    .textInk(style)
                    .inkStart(text, style),
            )
            return@Column
        }
        Spacer(Modifier.height(Space.HeaderToRows))
        LazyColumn {
            items(playlists, key = { it.key }) { playlist ->
                ListRow(
                    title = playlist.title,
                    subtitle = playlist.subtitle,
                    lead = RowLead.Cover,
                    onClick = { onPick(playlist) },
                    leading = {
                        Artwork(playlist.art, Modifier.size(Sizes.RowArt), playlist.kind, name = playlist.title)
                    },
                )
            }
        }
    }
}
