package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** How many rows from either end of a paged list it asks for more. */
private const val LOAD_AHEAD = 10

/**
 * Asks a paged list for more as it nears its end ([onNearEnd]), and its start when it pages both
 * ways ([onNearStart]): each time the rows left there come within [LOAD_AHEAD].
 */
@Composable
fun LoadAhead(list: LazyListState, onNearEnd: () -> Unit, onNearStart: (() -> Unit)? = null) {
    val end by rememberUpdatedState(onNearEnd)
    val start by rememberUpdatedState(onNearStart)
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.run { totalItemsCount - (visibleItemsInfo.lastOrNull()?.index ?: 0) } }
            .distinctUntilChanged()
            .filter { it <= LOAD_AHEAD }
            .collect { end() }
    }
    if (onNearStart != null) {
        LaunchedEffect(list) {
            snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().filter { it <= LOAD_AHEAD }.collect {
                start?.invoke()
            }
        }
    }
}
