package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.data.downloads.DownloadState
import io.github.hazymorning.amsel.ui.components.ListRow
import io.github.hazymorning.amsel.ui.components.dateText
import io.github.hazymorning.amsel.ui.components.leftText
import io.github.hazymorning.amsel.ui.components.lengthText
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR

@Composable
internal fun EpisodeRow(
    episode: EpisodeUi,
    playing: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    download: DownloadState? = null,
) {
    ListRow(
        title = episode.title,
        subtitle = listOfNotNull(downloadLine(download), episodeLine(episode)).joinToString(DOT_SEPARATOR).ifBlank {
            null
        },
        modifier = modifier,
        twoLines = true,
        playing = playing,
        quiet = episode.played && !playing,
        onClick = onClick,
        onLongClick = onMore,
    )
}

@Composable
private fun episodeLine(episode: EpisodeUi): String? {
    val date = episode.date?.let { dateText(it) }
    val left = episode.remainingSec?.takeIf { !episode.played }?.let { leftText(it) }
    val length = episode.durationSec?.let { lengthText(it) }
    val played = stringResource(R.string.pages_played).takeIf { episode.played }
    return listOfNotNull(played, date, left ?: length).joinToString(DOT_SEPARATOR).ifBlank { null }
}

@Composable
private fun downloadLine(download: DownloadState?): String? = when (download) {
    null -> null
    DownloadState.Done -> stringResource(R.string.downloaded)
    is DownloadState.Running -> stringResource(R.string.downloading_percent, (download.fraction * PERCENT).toInt())
    is DownloadState.Waiting -> stringResource(R.string.download_waiting)
    is DownloadState.Failed -> stringResource(R.string.download_stopped)
}

private const val PERCENT = 100
