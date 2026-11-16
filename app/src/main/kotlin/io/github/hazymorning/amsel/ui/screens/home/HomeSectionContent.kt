package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.data.home.SectionLayout
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.data.home.SectionSource
import io.github.hazymorning.amsel.ui.components.CardPlaceholder
import io.github.hazymorning.amsel.ui.components.MediaRow
import io.github.hazymorning.amsel.ui.components.MediaTile
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.RowPlaceholder
import io.github.hazymorning.amsel.ui.components.ShelfRow
import io.github.hazymorning.amsel.ui.components.TilePlaceholder
import io.github.hazymorning.amsel.ui.components.headerToRow
import io.github.hazymorning.amsel.ui.components.inkStart
import io.github.hazymorning.amsel.ui.components.overhang
import io.github.hazymorning.amsel.ui.components.rowInkBottom
import io.github.hazymorning.amsel.ui.components.shelfTileWidth
import io.github.hazymorning.amsel.ui.components.textInk
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.secondLine
import io.github.hazymorning.amsel.ui.model.twoLines
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.spaced

/**
 * A section's items in its layout, from its first edge to its last ink: a list's last row hangs
 * its inset below, so the next section measures from the cover's edge as it does from a tile's.
 */
@Composable
internal fun SectionContent(
    section: SectionUi,
    onOpen: (MediaUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    modifier: Modifier = Modifier,
    onResume: (MediaUi) -> Unit = onOpen,
) {
    val listening = section.option == SectionOption.ContinueListening
    if (listening && section.layout == SectionLayout.Big) {
        ListeningShelf(section.items, onResume, onMore, modifier)
        return
    }
    // A card shows its title alone.
    val items = if (listening && section.layout != SectionLayout.Grid) withTimeLeft(section.items) else section.items
    when (section.layout) {
        SectionLayout.Grid -> CardGrid(items.size, modifier) { index, card ->
            val item = items[index]
            key(item.key) {
                HomeCard(item, onClick = { onOpen(item) }, onMore = { onMore(item) }, card)
            }
        }

        SectionLayout.Small, SectionLayout.Row, SectionLayout.Big -> Shelf(
            items,
            tileWidth(section.layout),
            tileGap(section.layout),
            twoLines = section.option.twoLineCaptions,
            onOpen = onOpen,
            onMore = onMore,
            modifier = modifier,
        )

        SectionLayout.List -> Column(modifier.overhang(bottom = rowInkBottom(RowLead.Cover, twoLines = true))) {
            val twoLines = items.twoLines()
            items.forEach { item ->
                key(item.key) {
                    MediaRow(
                        item,
                        onClick = { onOpen(item) },
                        onMore = { onMore(item) },
                        subtitle = if (twoLines) item.secondLine() else null,
                    )
                }
            }
        }
    }
}

/** Static twins of [SectionContent], first load only. */
@Composable
internal fun SectionSkeleton(section: SectionUi, modifier: Modifier = Modifier) {
    val twoLines = section.option.twoLineCaptions
    if (section.option == SectionOption.ContinueListening && section.layout == SectionLayout.Big) {
        ListeningSkeleton(modifier)
        return
    }
    when (section.layout) {
        SectionLayout.Grid -> CardGrid(SKELETON_CARDS, modifier) { _, card -> CardPlaceholder(card) }

        SectionLayout.Small, SectionLayout.Row, SectionLayout.Big -> ShelfSkeleton(
            if (section.layout == SectionLayout.Small) SKELETON_SMALL_TILES else SKELETON_TILES,
            tileWidth(section.layout),
            tileGap(section.layout),
            twoLines,
            modifier,
        )

        SectionLayout.List -> Column(modifier.overhang(bottom = rowInkBottom(RowLead.Cover, twoLines = true))) {
            repeat(SKELETON_ROWS) { RowPlaceholder(it) }
        }
    }
}

/**
 * Artists and stations have no second line, and a playlist's would mostly repeat its owner, so
 * their tiles end on the title's baseline. It follows the option, not the items, so a skeleton is
 * as tall as what replaces it; mixed options keep two lines, as their tiles share baselines.
 */
internal val SectionOption.twoLineCaptions: Boolean
    get() = source !in OneLineSources

private val OneLineSources = setOf(SectionSource.Artists, SectionSource.Radio, SectionSource.Playlists)

/** From a header's baseline (or the sentence's chips) to the layout's top. */
@Composable
internal fun contentGap(layout: SectionLayout): Dp = if (layout == SectionLayout.List) {
    headerToRow(RowLead.Cover, twoLines = true) + Space.S2
} else {
    with(LocalDensity.current) { spaced(Space.Group) }
}

/** What edit mode shows for a section with nothing in it, where its items would be. */
@Composable
internal fun EmptySectionNote(option: SectionOption, modifier: Modifier = Modifier) {
    val text = stringResource(
        when (option) {
            SectionOption.Pinned -> R.string.home_section_empty_pinned
            else -> R.string.home_section_empty
        },
    )
    val style = MaterialTheme.typography.bodyMedium
    Text(
        text,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Keyline.Margin)
            .textInk(style)
            .inkStart(text, style),
    )
}

@Composable
private fun Shelf(
    items: List<MediaUi>,
    width: Dp,
    gap: Dp,
    twoLines: Boolean,
    onOpen: (MediaUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    ShelfRow(items, key = { it.key }, modifier, gap = gap) { item ->
        MediaTile(
            item,
            onClick = { onOpen(item) },
            onMore = { onMore(item) },
            modifier = Modifier.width(width),
            twoLines = twoLines,
        )
    }
}

@Composable
private fun ShelfSkeleton(count: Int, width: Dp, gap: Dp, twoLines: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState(), enabled = false)
            .padding(horizontal = Keyline.Margin),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        repeat(count) { TilePlaceholder(it, Modifier.width(width), twoLines = twoLines) }
    }
}

/** A shelf's covers in [layout]: small, a tile, or big. */
@Composable
internal fun tileWidth(layout: SectionLayout): Dp = when (layout) {
    SectionLayout.Small -> smallTileWidth()
    SectionLayout.Big -> bigTileWidth()
    else -> shelfTileWidth()
}

internal fun tileGap(layout: SectionLayout): Dp = if (layout == SectionLayout.Small) Space.SmallTiles else Space.Tiles

/** The layouts that are a sideways shelf of covers. */
internal val SectionLayout.isShelf: Boolean
    get() = this == SectionLayout.Small || this == SectionLayout.Row || this == SectionLayout.Big

/** Big covers: two to a screen; never smaller than a tile, which large text widens. */
@Composable
internal fun bigTileWidth(): Dp {
    val screen = LocalWindowInfo.current.containerDpSize.width
    return min(max(Sizes.bigTile(screen), shelfTileWidth()), screen - Keyline.Margin * 2)
}

/** Small covers: three to a screen and a third of the next; with large text, as wide as their titles need. */
@Composable
internal fun smallTileWidth(): Dp {
    val em = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.fontSize.toDp() }
    val small = max(Sizes.smallTile(LocalWindowInfo.current.containerDpSize.width), em * SMALL_CAPTION_EMS)
    return min(small, shelfTileWidth())
}

/** The fewest letters a small cover's title keeps at large font sizes. */
private const val SMALL_CAPTION_EMS = 6

private const val SKELETON_CARDS = 4
private const val SKELETON_TILES = 3
private const val SKELETON_SMALL_TILES = 4
private const val SKELETON_ROWS = 3
