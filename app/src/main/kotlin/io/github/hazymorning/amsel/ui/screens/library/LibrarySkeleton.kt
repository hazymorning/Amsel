package io.github.hazymorning.amsel.ui.screens.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.RowPlaceholder
import io.github.hazymorning.amsel.ui.components.TileGrid
import io.github.hazymorning.amsel.ui.components.TilePlaceholder

private const val SKELETON_ROWS = 12

@Composable
internal fun LibrarySkeleton(kind: LibraryKind, header: LibraryHeader, insets: PaddingValues, placeholders: Boolean) {
    val loading = stringResource(R.string.library_loading)
    fun Modifier.announce(i: Int) = clearAndSetSemantics { if (i == 0) contentDescription = loading }
    if (kind.covers) {
        TileGrid(
            insets,
            userScrollEnabled = false,
        ) {
            header(header)
            if (placeholders) {
                items(SKELETON_ROWS, contentType = { "skeleton" }) { i ->
                    TilePlaceholder(i, Modifier.announce(i))
                }
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(top = insets.calculateTopPadding()), userScrollEnabled = false) {
            header(header)
            if (placeholders) {
                items(SKELETON_ROWS, contentType = { "skeleton" }) { i ->
                    RowPlaceholder(i, Modifier.announce(i))
                }
            }
        }
    }
}
