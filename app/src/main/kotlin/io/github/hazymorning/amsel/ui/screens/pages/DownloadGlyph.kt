package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.data.downloads.DownloadState

/**
 * A page's download, beside its play button: the arrow downloads, a ring around a stop shows how
 * far it has come and stops it, the filled arrow says it is on the phone and offers to remove it.
 */
@Composable
internal fun downloadGlyph(state: DownloadState?, onDownload: () -> Unit, onCancel: () -> Unit, onRemove: () -> Unit) =
    when (state) {
        null, is DownloadState.Failed -> PageGlyph(
            R.drawable.ic_download,
            stringResource(R.string.download),
            onClick = onDownload,
        )

        is DownloadState.Waiting -> PageGlyph(
            R.drawable.ic_download_stop,
            stringResource(R.string.download_cancel),
            PageGlyph.Ring(progress = null),
            onCancel,
        )

        is DownloadState.Running -> PageGlyph(
            R.drawable.ic_download_stop,
            stringResource(R.string.download_cancel),
            PageGlyph.Ring { state.fraction },
            onCancel,
        )

        DownloadState.Done -> PageGlyph(
            R.drawable.ic_downloaded,
            stringResource(R.string.download_remove),
            onClick = onRemove,
        )
    }
