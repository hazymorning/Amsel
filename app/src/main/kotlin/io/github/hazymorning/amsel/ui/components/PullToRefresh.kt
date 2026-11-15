package io.github.hazymorning.amsel.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes

/**
 * [content] must scroll vertically (a lazy list or verticalScroll), or the pull never starts. It
 * gets the modifier each of its items carries: TalkBack cannot pull, so every item offers the
 * refresh among its actions, as Up next's rows offer their moves.
 * The disc rests over the list, which never moves under it.
 */
@Composable
fun AmselPullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(itemActions: Modifier) -> Unit,
) {
    val state = rememberPullToRefreshState()
    val refresh by rememberUpdatedState(onRefresh)
    val refreshLabel = stringResource(R.string.refresh)
    val itemActions = remember(refreshLabel) {
        val actions = listOf(
            CustomAccessibilityAction(refreshLabel) {
                refresh()
                true
            },
        )
        Modifier.semantics { customActions = actions }
    }
    val busyLabel = stringResource(R.string.refreshing)
    val colors = MaterialTheme.colorScheme
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            // Pages run under the status bar, so the disc keeps below it; it parts from the list by tone.
            PullToRefreshDefaults.IndicatorBox(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                shape = Shapes.Circle,
                containerColor = colors.surfaceContainerLow,
                elevation = 0.dp,
            ) {
                Crossfade(refreshing, animationSpec = Motion.effects(), label = "refresh") { spinning ->
                    Box(
                        Modifier.size(Sizes.Icon).clearAndSetSemantics {
                            if (spinning) {
                                contentDescription = busyLabel
                                liveRegion = LiveRegionMode.Polite
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (spinning) {
                            Spinner(color = colors.onSurfaceVariant)
                        } else {
                            Spinner(color = colors.onSurfaceVariant, progress = {
                                state.distanceFraction.coerceIn(0f, 1f)
                            })
                        }
                    }
                }
            }
        },
        content = { content(itemActions) },
    )
}
