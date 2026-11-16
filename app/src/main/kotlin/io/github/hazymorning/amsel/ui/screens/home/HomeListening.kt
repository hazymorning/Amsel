package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.AHEAD_ALPHA
import io.github.hazymorning.amsel.ui.components.Artwork
import io.github.hazymorning.amsel.ui.components.HEARD_ALPHA
import io.github.hazymorning.amsel.ui.components.ShelfRow
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.inkStart
import io.github.hazymorning.amsel.ui.components.leftText
import io.github.hazymorning.amsel.ui.components.lengthText
import io.github.hazymorning.amsel.ui.components.shelfTileWidth
import io.github.hazymorning.amsel.ui.components.textInk
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Radius
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.SmoothShape
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.capHeight
import io.github.hazymorning.amsel.ui.theme.isNight
import io.github.hazymorning.amsel.ui.theme.rememberCoverColors
import io.github.hazymorning.amsel.ui.theme.rememberPress
import io.github.hazymorning.amsel.ui.theme.secondLine
import io.github.hazymorning.amsel.ui.theme.spaced

/**
 * Continue listening as upright cards, each washed in its cover's hue and as wide as a shelf's
 * tiles, so they stand over the covers below: a tap resumes where the person stopped; a long press
 * opens its actions, which lead to its page.
 */
@Composable
internal fun ListeningShelf(
    items: List<MediaUi>,
    onResume: (MediaUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val width = shelfTileWidth()
    val card = rememberCardMeasure(width)
    ShelfRow(items, key = { it.key }, modifier) { item ->
        ListeningCard(item, card, onPlay = { onResume(item) }, onMore = { onMore(item) })
    }
}

/** The cards' twins while Continue listening loads for the first time: their size, their shape. */
@Composable
internal fun ListeningSkeleton(modifier: Modifier = Modifier) {
    val card = rememberCardMeasure(shelfTileWidth())
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState(), enabled = false)
            .padding(horizontal = Keyline.Margin),
        horizontalArrangement = Arrangement.spacedBy(Space.Tiles),
    ) {
        repeat(SKELETON_CARDS) {
            Box(
                Modifier.size(
                    card.width,
                    card.height,
                ).clip(CardShape).background(MaterialTheme.colorScheme.surfaceContainerLow),
            )
        }
    }
}

/** Continue listening in any other layout: what is left joins each item's second line. */
@Composable
internal fun withTimeLeft(items: List<MediaUi>): List<MediaUi> = items.map { item ->
    val left = item.remainingSec?.let { leftText(it) } ?: return@map item
    item.copy(subtitle = listOfNotNull(item.subtitle, left).joinToString(DOT_SEPARATOR))
}

/**
 * Where a card's lines stand, from its top. Under the cover, two lines that say what it is; then,
 * a group's gap further, what is left, 16 over the line along the card's foot.
 */
private class CardMeasure(val width: Dp, val cover: Dp, val words: Dp, val title: Dp, val second: Dp, val height: Dp)

@Composable
private fun rememberCardMeasure(width: Dp): CardMeasure {
    val type = MaterialTheme.typography
    val density = LocalDensity.current
    return remember(width, density, type) {
        with(density) {
            val cover = width - Frame * 2
            val title = Frame + cover + spaced(Space.Caption) + capHeight(type.titleSmall)
            val second = secondLine(type.bodyMedium)
            val foot = spaced(Space.Group) + capHeight(type.bodyMedium) + spaced(Space.S4) + Sizes.ProgressLine
            CardMeasure(width, cover, Frame + Radius.under(Radius.cover(cover)), title, second, title + second + foot)
        }
    }
}

/**
 * Something to pick up again as a card of its cover's paper: the cover in a frame of 12, the card's
 * corners the cover's plus that frame. Under the cover what it is: the title's two lines, or its one
 * and who wrote or made it, so no card has a hole where another's title wraps. Over the card's foot
 * what is left, and along it, edge to edge in the ink of every progress line, how far the person got.
 */
@Composable
private fun ListeningCard(item: MediaUi, card: CardMeasure, onPlay: () -> Unit, onMore: () -> Unit) {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val presses = remember { MutableInteractionSource() }
    val night = colors.isNight
    val plain = colors.onSurface.copy(alpha = CARD_INK).compositeOver(colors.surfaceContainerLow)
    val hue = rememberCoverColors(item.art)
    val paper: Color = hue?.wash(colors.surface, night) ?: plain
    Box(
        Modifier
            .size(card.width, card.height)
            .clip(CardShape)
            .background(paper)
            .combinedClickable(
                interactionSource = presses,
                indication = rememberPress(shape = null),
                role = Role.Button,
                onClickLabel = stringResource(R.string.resume),
                onClick = onPlay,
                onLongClickLabel = stringResource(R.string.more_options_for, item.title),
                onLongClick = onMore,
            ),
    ) {
        Artwork(
            item.art,
            Modifier.padding(Frame).size(card.cover),
            item.kind,
            interactionSource = presses,
            name = item.title,
        )
        WhatItIs(item, card, Modifier.padding(horizontal = card.words))
        item.remainingSec?.let { left ->
            val text = leftText(left)
            Words(
                text,
                type.bodyMedium,
                color = colors.onSurfaceVariant,
                shorter = lengthText(left),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = card.words,
                        end = card.words,
                        bottom = with(LocalDensity.current) { spaced(Space.S4) } + Sizes.ProgressLine,
                    )
                    .textInk(type.bodyMedium)
                    .inkStart(text, type.bodyMedium),
            )
        }
        FootLine({ item.progress ?: 0f }, colors.onSurface, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * How far through, as the card's own foot: edge to edge, square at the moving end, its two ends
 * rounded by the card's corners, heard and ahead in the ink every progress line has. Anything begun
 * fills the first corner's curve, so a book a few minutes in still shows where the line starts.
 */
@Composable
private fun FootLine(fraction: () -> Float, ink: Color, modifier: Modifier) {
    Canvas(modifier.fillMaxWidth().height(Sizes.ProgressLine)) {
        drawRect(ink.copy(alpha = AHEAD_ALPHA))
        val f = fraction().coerceIn(0f, 1f)
        if (f <= 0f) return@Canvas
        val begun = CardCorner.toPx()
        val along = begun + f * (size.width - begun)
        val start = if (layoutDirection == LayoutDirection.Rtl) size.width - along else 0f
        drawRect(ink.copy(alpha = HEARD_ALPHA), Offset(start, 0f), Size(along, size.height))
    }
}

/** The title on its two lines; while it takes one, its author or podcast on the second. */
@Composable
private fun WhatItIs(item: MediaUi, card: CardMeasure, modifier: Modifier) {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val by = item.subtitle?.takeIf { it.isNotBlank() }
    Layout(
        content = {
            Words(
                item.title,
                type.titleSmall,
                color = colors.onSurface,
                maxLines = 2,
                modifier = Modifier.inkStart(item.title, type.titleSmall),
            )
            if (by !=
                null
            ) {
                Words(
                    by,
                    type.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.inkStart(by, type.bodyMedium),
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val title = measurables[0].measure(loose)
        val first = title[FirstBaseline]
        val oneLine = first == title[LastBaseline]
        val author = if (oneLine) measurables.getOrNull(1)?.measure(loose) else null
        val titleAt = card.title.roundToPx() - first
        layout(constraints.maxWidth, constraints.maxHeight) {
            title.placeRelative(0, titleAt)
            author?.placeRelative(0, titleAt + first + card.second.roundToPx() - author[FirstBaseline])
        }
    }
}

/** The cover's frame inside the card: its edge plus half its corner is where the words stand, 16 in, as from any filled shape's sides. */
private val Frame = Space.S3

/** The card's corners: the cover's and its frame, so the two curves run side by side. */
private val CardCorner = Radius.Thumb + Frame

private val CardShape = SmoothShape(CardCorner)

/** The card's paper where its cover lends no hue: Home's cards' own. */
private const val CARD_INK = 0.03f

private const val SKELETON_CARDS = 2
