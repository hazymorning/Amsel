package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.TrackUi
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.lineHeight
import io.github.hazymorning.amsel.ui.theme.tabular

/** Decide [twoLines] per list, not per row, so one guest artist does not change an album's rhythm. */
@Composable
fun TrackRow(
    track: TrackUi,
    lead: RowLead,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    showAlbum: Boolean = false,
    subtitle: String? = if (showAlbum &&
        track.album != null
    ) {
        "${track.artist}$DOT_SEPARATOR${track.album}"
    } else {
        track.artist
    },
    twoLines: Boolean = subtitle != null,
    /** The list's [numberLeadWidth], for [RowLead.Glyph]; unused beside a cover. */
    leadWidth: Dp = Sizes.Lead,
) {
    val hasSecond = twoLines && !subtitle.isNullOrBlank()
    ListRow(
        title = track.title,
        subtitle = subtitle,
        modifier = modifier,
        lead = lead,
        twoLines = lead == RowLead.Cover || twoLines,
        playing = playing,
        onClick = onClick,
        onLongClick = onMore,
        leadWidth = leadWidth,
        leading = {
            if (lead == RowLead.Cover) {
                Artwork(track.art, Modifier.size(Sizes.RowArt), MediaKind.Track, name = track.album ?: track.title)
            } else {
                TrackNumber(track.number, playing, onTitleLine = hasSecond)
            }
        },
    )
}

/** [onTitleLine] lifts the number to the title's line in a two-line row; the play bars replace it while playing. */
@Composable
internal fun TrackNumber(number: Int?, playing: Boolean, onTitleLine: Boolean) {
    val type = MaterialTheme.typography
    val lift = if (onTitleLine) with(LocalDensity.current) { lineHeight(type.bodyMedium) } / 2 else null
    val place = Modifier.fillMaxWidth().then(if (lift != null) Modifier.offset(y = -lift) else Modifier)
    if (playing) {
        Box(place, contentAlignment = Alignment.CenterStart) { PlayBars() }
        return
    }
    LeadNumber(number?.toString().orEmpty(), place)
}

@Composable
fun LeadNumber(text: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyMedium.tabular()
    Box(modifier) {
        Text(
            text,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.trimStartBearing(text, style),
        )
    }
}

fun formatDuration(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
