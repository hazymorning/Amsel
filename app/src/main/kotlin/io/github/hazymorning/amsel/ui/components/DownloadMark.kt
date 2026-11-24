package io.github.hazymorning.amsel.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.model.DownloadMark

/** The downloads by uri, from the app's root, so every row and tile marks its own item. */
val LocalDownloadMarks = staticCompositionLocalOf<State<Map<String, DownloadMark>>> { mutableStateOf(emptyMap()) }

/** [key]'s mark; a row changes when its own does, not each time another download moves on. */
@Composable
fun downloadMark(key: String): DownloadMark? {
    val marks = LocalDownloadMarks.current
    val mark by remember(marks, key) { derivedStateOf { marks.value[key] } }
    return mark
}

/** [key]'s mark as the glyph before an item's second line, or null when the phone holds none of it. */
@Composable
fun downloadMarkGlyph(key: String): (@Composable () -> Unit)? =
    downloadMark(key)?.let { mark -> { DownloadMarkGlyph(mark) } }

/** The filled arrow once the item is on the phone, a turning ring while it comes, as large as a capital. */
@Composable
fun DownloadMarkGlyph(mark: DownloadMark, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    when (mark) {
        // In ink, beside the quieter words: at a glance, which books are on the phone.
        DownloadMark.Done -> AlignedGlyph(
            R.drawable.ic_downloaded,
            modifier,
            tint = MaterialTheme.colorScheme.onSurface,
            size = MarkSize,
            contentDescription = stringResource(R.string.downloaded),
        )

        DownloadMark.Coming -> {
            val coming = stringResource(R.string.download_waiting)
            Spinner(modifier.semantics { contentDescription = coming }, size = MarkSize, color = tint)
        }
    }
}

private val MarkSize = 14.dp
