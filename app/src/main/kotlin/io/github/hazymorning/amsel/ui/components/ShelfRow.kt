package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space

@Composable
fun <T> ShelfRow(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    gap: Dp = Space.Tiles,
    tile: @Composable (T) -> Unit,
) {
    LazyRow(
        modifier,
        state = state,
        contentPadding = PaddingValues(horizontal = Keyline.Margin),
        horizontalArrangement = Arrangement.spacedBy(gap),
        flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
    ) {
        items(items, key = key, contentType = { "tile" }) { tile(it) }
    }
}
