package io.github.hazymorning.amsel.ui.screens.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.data.home.SectionSource
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.components.EmptyState
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.MediaRow
import io.github.hazymorning.amsel.ui.components.MediaTile
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.RowPlaceholder
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.components.TileGrid
import io.github.hazymorning.amsel.ui.components.TilePlaceholder
import io.github.hazymorning.amsel.ui.components.plus
import io.github.hazymorning.amsel.ui.components.rowInkBottom
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.secondLine
import io.github.hazymorning.amsel.ui.model.twoLines
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.rememberOpenItem
import io.github.hazymorning.amsel.ui.rememberRetry
import io.github.hazymorning.amsel.ui.theme.Space

/** "See all" from a Home section: albums as a grid of tiles, anything else as rows. */
@Composable
fun SeeAllScreen(
    state: SeeAllUiState,
    onBack: () -> Unit,
    onOpen: (MediaUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    named: String? = null,
) {
    val title = named ?: stringResource(sectionTitle(state.option, state.fallback))
    val grid = rememberLazyGridState()
    val rows = rememberLazyListState()
    MaterialScreen(modifier, bar = { PageTopBar(title, onBack) }) { insets ->
        ScrollEdges(if (state.grid) grid else rows)
        val body = Modifier.fillMaxSize()
        val message = Modifier.fillMaxSize().padding(insets)
        when {
            state.items.isNotEmpty() && state.grid -> TileGrid(
                insets.plus(top = Space.S2, bottom = Space.ListEnd),
                body,
                state = grid,
            ) {
                items(state.items, key = { it.key }, contentType = { SeeAllSlot.Item }) { item ->
                    MediaTile(item, onClick = { onOpen(item) }, onMore = { onMore(item) })
                }
            }

            state.items.isNotEmpty() -> LazyColumn(body, rows, contentPadding = rowsPadding(insets)) {
                val twoLines = state.items.twoLines()
                items(state.items, key = { it.key }, contentType = { SeeAllSlot.Item }) { item ->
                    MediaRow(
                        item,
                        onClick = { onOpen(item) },
                        onMore = { onMore(item) },
                        subtitle = if (twoLines) item.secondLine() else null,
                    )
                }
            }

            state.loading -> SeeAllSkeleton(albums = state.grid, insets, body)

            state.failure != null -> EmptyState(
                R.drawable.ic_offline,
                stringResource(state.failure),
                message,
                actionLabel = stringResource(R.string.retry),
                onAction = onRetry,
            )

            else -> EmptyState(state.option.source.glyph, stringResource(R.string.home_section_empty), message)
        }
    }
}

private enum class SeeAllSlot { Item, Placeholder }

@get:DrawableRes
private val SectionSource.glyph: Int
    get() = when (this) {
        SectionSource.Pinned -> R.drawable.ic_pin
        SectionSource.RecentlyPlayed, SectionSource.Albums -> R.drawable.ic_album
        SectionSource.Playlists -> R.drawable.ic_playlist
        SectionSource.Artists -> R.drawable.ic_artist
        SectionSource.Radio -> R.drawable.ic_radio
        SectionSource.Audiobooks -> R.drawable.ic_audiobook
        SectionSource.Podcasts -> R.drawable.ic_podcast
        SectionSource.Search -> R.drawable.ic_search
    }

/** The first cover 8 under the bar, as a row keeps it 8 under its top; the last ink 32 above the dock. */
@Composable
private fun rowsPadding(insets: PaddingValues) =
    insets.plus(bottom = Space.ListEnd - rowInkBottom(RowLead.Cover, twoLines = true))

/** First load only: the grid's tiles or the list's rows as tonal blocks. */
@Composable
private fun SeeAllSkeleton(albums: Boolean, insets: PaddingValues, modifier: Modifier = Modifier) {
    val loading = stringResource(R.string.loading)
    val quiet = modifier.clearAndSetSemantics { contentDescription = loading }
    if (albums) {
        TileGrid(
            insets.plus(top = Space.S2, bottom = Space.ListEnd),
            quiet,
            userScrollEnabled = false,
        ) {
            items(PLACEHOLDERS, contentType = { SeeAllSlot.Placeholder }) { TilePlaceholder(it) }
        }
    } else {
        LazyColumn(quiet, contentPadding = rowsPadding(insets), userScrollEnabled = false) {
            items(PLACEHOLDERS, contentType = { SeeAllSlot.Placeholder }) { RowPlaceholder(it) }
        }
    }
}

private const val PLACEHOLDERS = 12

@Composable
fun SeeAllRoute(navigator: Navigator, option: SectionOption, modifier: Modifier = Modifier, title: String? = null) {
    val graph = LocalContext.current.graph
    val viewModel = viewModel {
        SeeAllViewModel(option, graph.data.home, { graph.data.home.all(it, SEE_ALL) }, graph.mapper::media)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    SeeAllScreen(
        state = state,
        onBack = { navigator.back() },
        onOpen = rememberOpenItem(navigator, asks = true),
        onMore = { actions.more(ActionTarget.Media(it)) },
        onRetry = rememberRetry(viewModel::retry),
        named = title,
        modifier = modifier,
    )
}

/** How many items See all asks for: more than anyone scrolls through from Home. */
private const val SEE_ALL = 500
