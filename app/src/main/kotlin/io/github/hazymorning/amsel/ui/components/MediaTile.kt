package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.secondLine
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.rememberPress
import io.github.hazymorning.amsel.ui.theme.secondLine
import io.github.hazymorning.amsel.ui.theme.spaced

/** Tiles in columns between the screen's margins: the Library, See all and their skeletons. */
@Composable
fun TileGrid(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    userScrollEnabled: Boolean = true,
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(gridTileMin()),
        modifier = modifier,
        state = state,
        contentPadding = padding.plus(horizontal = Keyline.Margin),
        horizontalArrangement = Arrangement.spacedBy(Space.Tiles),
        verticalArrangement = Arrangement.spacedBy(Space.TileRows),
        userScrollEnabled = userScrollEnabled,
        content = content,
    )
}

/** As many columns as [TileGrid] makes of [width], for tiles laid out in rows of a list. */
@Composable
fun tileColumns(width: Dp): Int =
    ((width - Keyline.Margin * 2 + Space.Tiles) / (gridTileMin() + Space.Tiles)).toInt().coerceAtLeast(1)

/** At least [Sizes.GridTileMin], wider with large text so a caption keeps its words. */
@Composable
private fun gridTileMin(): Dp =
    Sizes.GridTileMin * (1f + (LocalDensity.current.fontScale.coerceAtLeast(1f) - 1f) * GRID_GROWTH)

/** How much of the font's growth the grid's columns follow. */
private const val GRID_GROWTH = 0.8f

/** The fewest letters a tile's title keeps at large font sizes; the tile widens before it goes below. */
private const val CAPTION_EMS = 9

/** A shelf's tile: two to a screen with a third's width left for the next, so every shelf ends alike. */
@Composable
fun shelfTileWidth(): Dp {
    val em = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.fontSize.toDp() }
    return max(Sizes.tile(LocalWindowInfo.current.containerDpSize.width), em * CAPTION_EMS)
}

/**
 * A square cover and its caption under it, starting where the cover's corner ends ([TileCaption]):
 * the title, and right under it the second line, the server's or what the item is ([secondLine]);
 * [twoLines] false leaves it out (a shelf of artists, stations or playlists). Every tile alike: an
 * artist's picture is a cover like any other, its name on the margin. No menu: a long press opens
 * the item's actions, and the page it opens shows them all.
 */
@Composable
fun MediaTile(
    item: MediaUi,
    onClick: () -> Unit,
    /** The long press; null where a long press does something else (moving a pin in Edit Home). */
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    twoLines: Boolean = true,
    playing: Boolean = false,
) {
    val type = MaterialTheme.typography
    val titleStyle = type.titleSmall
    val (line, gap) = with(LocalDensity.current) {
        secondLine(type.bodyMedium) to spaced(Space.Caption)
    }
    val presses = remember { MutableInteractionSource() }
    val nowPlaying = stringResource(R.string.player_now_playing)
    Column(
        modifier
            .combinedClickable(
                interactionSource = presses,
                indication = rememberPress(shape = null),
                role = Role.Button,
                onClick = onClick,
                onLongClickLabel = onMore?.let { stringResource(R.string.more_options_for, item.title) },
                onLongClick = onMore,
            )
            // The ring says it to the eye; TalkBack hears it as a row does.
            .then(if (playing) Modifier.semantics { stateDescription = nowPlaying } else Modifier),
    ) {
        Artwork(
            item.art,
            Modifier.fillMaxWidth(),
            item.kind,
            interactionSource = presses,
            name = item.title,
        )
        val second = if (twoLines) item.secondLine() else null
        val mark = downloadMarkGlyph(item.key)
        TileCaption(
            titleStyle,
            type.bodyMedium,
            title = { lines ->
                Words(
                    item.title,
                    titleStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = lines,
                    modifier = Modifier
                        .underCover()
                        .fillMaxWidth()
                        .padding(top = gap)
                        .textInk(titleStyle)
                        .inkStart(item.title, titleStyle),
                )
            },
            second = second?.let { text ->
                {
                    val words: @Composable () -> Unit = {
                        Words(
                            text,
                            type.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .inkStart(text, type.bodyMedium),
                        )
                    }
                    Box(Modifier.underCover().paddingFromBaseline(top = line).textInk(type.bodyMedium, top = false)) {
                        when {
                            playing -> RingBefore(type.bodyMedium, words)
                            mark != null -> MarkBefore(type.bodyMedium, mark, words)
                            else -> words()
                        }
                    }
                }
            },
        )
    }
}
