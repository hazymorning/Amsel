package io.github.hazymorning.amsel.ui.screens.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.LibrarySort
import io.github.hazymorning.amsel.ui.components.ChipRowState
import io.github.hazymorning.amsel.ui.components.ChoiceChipRow
import io.github.hazymorning.amsel.ui.components.EmptyState
import io.github.hazymorning.amsel.ui.components.MediaRow
import io.github.hazymorning.amsel.ui.components.MediaTile
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.TabHeader
import io.github.hazymorning.amsel.ui.components.TextAction
import io.github.hazymorning.amsel.ui.components.TileGrid
import io.github.hazymorning.amsel.ui.components.baselineFrame
import io.github.hazymorning.amsel.ui.components.overhang
import io.github.hazymorning.amsel.ui.components.plus
import io.github.hazymorning.amsel.ui.components.rowGlyphBottom
import io.github.hazymorning.amsel.ui.components.rowGlyphTop
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.secondLine
import io.github.hazymorning.amsel.ui.model.twoLines
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.capHeight
import io.github.hazymorning.amsel.ui.theme.spaced

internal fun LazyListScope.header(header: LibraryHeader) {
    item(key = "header", contentType = "header") {
        Column {
            header.Content()
            Spacer(Modifier.height(SortToList - rowGlyphTop(RowLead.Cover, twoLines = true)))
        }
    }
}

internal fun LazyGridScope.header(header: LibraryHeader) {
    item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
        // The header keeps the screen's margins; the grid's own padding would double them.
        Box(Modifier.overhang(start = Keyline.Margin, end = Keyline.Margin, bottom = Space.TileRows - SortToList)) {
            header.Content()
        }
    }
}

@Composable
internal fun RowList(
    state: LibraryUiState,
    listState: LazyListState,
    header: LibraryHeader,
    insets: PaddingValues,
    onOpen: (MediaUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    modifier: Modifier,
    itemActions: Modifier,
) {
    val scrubber = state.letters.isNotEmpty()
    // Artists have no second line; playlists do as soon as one names its owner.
    val twoLines = remember(state.items) { state.items.twoLines() }
    Box(modifier) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = insets.plus(bottom = Space.ListEnd - rowGlyphBottom(RowLead.Cover, twoLines = true)),
        ) {
            header(header)
            items(state.items, key = { it.key }, contentType = { "row" }) { item ->
                MediaRow(
                    item,
                    onClick = { onOpen(item) },
                    onMore = { onMore(item) },
                    itemActions,
                    playing = item.key in state.playing,
                    subtitle = if (twoLines) item.secondLine() else null,
                )
            }
        }
        if (scrubber) LetterScrubber(state.letters, listState)
    }
}

@Composable
internal fun CoverGrid(
    state: LibraryUiState,
    gridState: LazyGridState,
    header: LibraryHeader,
    insets: PaddingValues,
    onOpen: (MediaUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    modifier: Modifier,
    itemActions: Modifier,
) {
    val scrubber = state.letters.isNotEmpty()
    // Stations have no second line: "Radio" under each would say what the chip says.
    val twoLines = remember(state.items) { state.items.twoLines() }
    Box(modifier) {
        TileGrid(
            insets.plus(bottom = Space.ListEnd),
            Modifier.fillMaxSize(),
            gridState,
        ) {
            header(header)
            items(state.items, key = { it.key }, contentType = { "tile" }) { item ->
                MediaTile(
                    item,
                    onClick = { onOpen(item) },
                    onMore = { onMore(item) },
                    itemActions,
                    twoLines = twoLines,
                    playing = item.key in state.playing,
                )
            }
        }
        if (scrubber) LetterScrubber(state.letters, gridState)
    }
}

/**
 * One glyph, one sentence under the header; every kind but playlists also points to where music is added.
 * It scrolls (by nothing) so it can be pulled: right after adding music is when a refresh is wanted.
 */
@Composable
internal fun KindEmptyState(
    kind: LibraryKind,
    onOpenWebUi: () -> Unit,
    header: LibraryHeader,
    insets: PaddingValues,
    modifier: Modifier,
    itemActions: Modifier,
) {
    val message = when (kind) {
        LibraryKind.Playlists -> R.string.library_empty_playlists
        LibraryKind.Albums -> R.string.library_empty_albums
        LibraryKind.Artists -> R.string.library_empty_artists
        LibraryKind.Audiobooks -> R.string.library_empty_audiobooks
        LibraryKind.Podcasts -> R.string.library_empty_podcasts
        LibraryKind.Radio -> R.string.library_empty_radio
        LibraryKind.Downloads -> R.string.library_empty_downloads
    }
    val action = if (kind == LibraryKind.Playlists ||
        kind == LibraryKind.Downloads
    ) {
        null
    } else {
        stringResource(R.string.add_music)
    }
    BoxWithConstraints(modifier) {
        Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().height(maxHeight).padding(insets)) {
            header.Content(sortable = false)
            EmptyState(
                kind.icon,
                stringResource(message),
                Modifier.fillMaxWidth().weight(1f),
                actionLabel = action,
                onAction = onOpenWebUi,
                messageModifier = itemActions,
            )
        }
    }
}

/**
 * The order in words, its capitals 16 under the chips; a tap moves to the next order. Its layout
 * ends on its baseline, so what follows measures from the letters.
 */
@Composable
private fun SortWords(sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
    val style = MaterialTheme.typography.bodyMedium
    val words = stringResource(sort.label) + ARROW
    val description = stringResource(R.string.library_sort_description, stringResource(sort.label))
    val haptics = LocalHapticFeedback.current
    val cap = with(LocalDensity.current) { capHeight(style) }
    Box(
        Modifier.fillMaxWidth().padding(
            horizontal = Keyline.Margin,
        ).baselineFrame(top = with(LocalDensity.current) { spaced(TabsToSort) } + cap, bottom = 0.dp),
    ) {
        TextAction(
            words,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onSort(sort.next())
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            contentDescription = description,
            style = style,
        )
    }
}

/** A thin space and a downward arrow: the list runs from the top of this order down. */
private const val ARROW = " ↓"

/** The orders in the order a tap steps through them; one order for the whole library. */
private val SORTS = listOf(LibrarySort.RecentlyPlayed, LibrarySort.RecentlyAdded, LibrarySort.AToZ)

private fun LibrarySort.next(): LibrarySort = SORTS[(SORTS.indexOf(this) + 1) % SORTS.size]

@get:StringRes
private val LibrarySort.label: Int
    get() = when (this) {
        LibrarySort.RecentlyPlayed -> R.string.library_sort_recently_played
        LibrarySort.RecentlyAdded -> R.string.library_sort_recently_added
        LibrarySort.AToZ -> R.string.library_sort_a_to_z
    }

/**
 * The title, the chips and the order: the top of every list on this screen; with nothing to sort,
 * no order. One kind alone, a place of its own in the bar, is the title and has no chips.
 */
internal class LibraryHeader(
    private val title: String,
    private val state: LibraryUiState,
    private val onSelectKind: (LibraryKind) -> Unit,
    private val onSort: (LibrarySort) -> Unit,
    /** One for every kind's list: the chips are one row to the eye, whichever list holds them. */
    private val chips: ChipRowState,
    private val onTitleHeight: (Int) -> Unit = {},
) {
    /** Downloads keep one order, the newest first: no order to choose. */
    @Composable
    fun Content(sortable: Boolean = state.kind != LibraryKind.Downloads) {
        Column {
            TabHeader(
                title,
                Modifier.onSizeChanged { onTitleHeight(it.height) },
                below = if (state.single) 0.dp else with(LocalDensity.current) { spaced(TitleToTabs) },
            )
            val kinds = state.kinds
            if (state.single) {
                // No chips to choose from: the order stands under the title as it does under them.
            } else if (kinds != null) {
                ChoiceChipRow(
                    kinds,
                    selected = state.kind,
                    onSelect = onSelectKind,
                    label = { stringResource(it.label) },
                    role = Role.Tab,
                    state = chips,
                )
            } else {
                // Its place is kept, unseen, until the kinds are known: nothing below moves when they come.
                ChoiceChipRow(
                    libraryKinds(LibraryKind.Default.visible, held = emptySet()),
                    selected = state.kind,
                    onSelect = {},
                    label = { stringResource(it.label) },
                    role = Role.Tab,
                    Modifier.alpha(0f).clearAndSetSemantics {},
                )
            }
            if (sortable) SortWords(state.sort, onSort)
        }
    }
}

/** From the order's baseline to the first cover's edge. */
internal val SortToList = Space.LabelToContent

/** From the title's baseline to the tabs, and from the tabs to the order's capitals. */
private val TitleToTabs = 20.dp
private val TabsToSort = 20.dp
