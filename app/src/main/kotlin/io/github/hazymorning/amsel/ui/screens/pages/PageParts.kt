package io.github.hazymorning.amsel.ui.screens.pages

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.Artwork
import io.github.hazymorning.amsel.ui.components.EmptyState
import io.github.hazymorning.amsel.ui.components.IconAction
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.PlayButton
import io.github.hazymorning.amsel.ui.components.RingAction
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.components.SkeletonArt
import io.github.hazymorning.amsel.ui.components.SkeletonText
import io.github.hazymorning.amsel.ui.components.TextLink
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.inkCentre
import io.github.hazymorning.amsel.ui.components.inkWidth
import io.github.hazymorning.amsel.ui.components.plus
import io.github.hazymorning.amsel.ui.components.textInk
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.capHeight
import io.github.hazymorning.amsel.ui.theme.isNight
import io.github.hazymorning.amsel.ui.theme.rememberCoverColors
import io.github.hazymorning.amsel.ui.theme.secondLine

internal enum class PageSlot { Art, Title, Actions, Header, Track, Shelf, AlbumRow, Message }

/** Index of the title item in every page's list; the bar shows the title once it has scrolled away. */
private const val TITLE_INDEX = 1

/**
 * A page: the slim bar with Back on the status strip, and the page's list running up under it. The
 * bar's title fades in only after the page's own title has scrolled away. [bottom] is the room
 * under the list's last ink before the dock.
 */
@Composable
internal fun PageScaffold(
    title: String?,
    list: LazyListState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bottom: Dp = Space.ListEnd,
    /** The cover whose hue washes the page's head. */
    art: ArtRef? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val titleShown by remember(list) { derivedStateOf { list.firstVisibleItemIndex > TITLE_INDEX } }
    val wash = coverWash(art)
    val washHeight = with(LocalDensity.current) { (pageCover() * WASH_COVERS).toPx() }
    MaterialScreen(modifier, bar = { PageTopBar(title, onBack, showTitle = titleShown, actions = actions) }) { insets ->
        ScrollEdges(list)
        LazyColumn(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    // The head scrolls away with its wash; past it the page is plain.
                    if (list.firstVisibleItemIndex != 0) return@drawBehind
                    val top = -list.firstVisibleItemScrollOffset.toFloat()
                    val brush = Brush.verticalGradient(
                        *WashStops.map { (at, share) -> at to wash.value.copy(alpha = wash.value.alpha * share) }
                            .toTypedArray(),
                        startY = top,
                        endY = top + washHeight,
                    )
                    drawRect(brush, topLeft = Offset(0f, top), size = Size(size.width, washHeight))
                },
            state = list,
            contentPadding = insets.plus(bottom = bottom),
            content = content,
        )
    }
}

/**
 * The page's head leans to its cover as Now Playing's whole page does: the same muted hue, behind
 * the cover and its title under the bar's clear strip, easing into the plain page by the list. A
 * cover with no hue worth borrowing, or none yet, leaves the page plain. The hue is there as soon
 * as it is known, with no fade.
 */
@Composable
private fun coverWash(art: ArtRef?): State<Color> {
    val colors = rememberCoverColors(art)
    val scheme = MaterialTheme.colorScheme
    return rememberUpdatedState(colors?.wash(scheme.surface, scheme.isNight) ?: scheme.surface.copy(alpha = 0f))
}

/** The wash reaches down this many cover heights from the screen's top. */
private const val WASH_COVERS = 2f

/** Whole through the bar and the cover's top, then easing out to nothing. */
private val WashStops = listOf(0f to 1f, 0.35f to 1f, 0.6f to 0.62f, 0.8f to 0.25f, 1f to 0f)

@Composable
internal fun pageCover(): Dp = Sizes.pageCover(LocalWindowInfo.current.containerDpSize.width)

/** The cover keeps clear of the strip's fade under the bar, so nothing veils it at rest. */
@Composable
internal fun PageArt(art: ArtRef?, kind: MediaKind, name: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(top = Space.Sibling), contentAlignment = Alignment.Center) {
        if (name != null) {
            Artwork(art, Modifier.size(pageCover()), kind, lifted = !MaterialTheme.colorScheme.isNight, name = name)
        } else {
            SkeletonArt(Modifier.size(pageCover()))
        }
    }
}

/**
 * The page's name in the Display style, centred under the cover, two lines at most and cut at a
 * word; a [line] (the artist) and [meta] under it. A null [title] draws a skeleton line, as does a
 * null [meta] that [hasMeta] says is coming.
 */
@Composable
internal fun PageTitle(
    title: String?,
    modifier: Modifier = Modifier,
    line: (@Composable () -> Unit)? = null,
    meta: String? = null,
    hasMeta: Boolean = false,
) {
    val type = MaterialTheme.typography
    val density = LocalDensity.current
    val toLine = with(density) { secondLine(type.bodyLarge) }
    // The details stand as far under the line as the line under the title, letters to letters.
    val toMeta = with(density) { toLine - capHeight(type.bodyLarge) + capHeight(type.bodyMedium) }
    val column = Sizes.pageColumn(LocalWindowInfo.current.containerDpSize.width)
    Column(
        modifier
            .fillMaxWidth()
            .wrapContentWidth()
            .width(column)
            .padding(top = Space.S8),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val style = type.displaySmall
        if (title != null) {
            Words(
                title,
                style,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .textInk(style)
                    .semantics { heading() },
            )
        } else {
            val loading = stringResource(R.string.loading)
            SkeletonText(
                style,
                fraction = 0.5f,
                alignment = Alignment.CenterHorizontally,
                inkTop = true,
                inkBottom = true,
                modifier = Modifier.semantics { contentDescription = loading },
            )
        }
        if (line != null) {
            Box(Modifier.paddingFromBaseline(top = toLine)) { line() }
        }
        if (hasMeta) {
            val place = Modifier.paddingFromBaseline(top = if (line != null) toMeta else toLine)
            if (meta != null) {
                Words(
                    meta,
                    type.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = place.fillMaxWidth().textInk(type.bodyMedium, top = false),
                )
            } else {
                SkeletonText(
                    type.bodyMedium,
                    place,
                    fraction = 0.4f,
                    alignment = Alignment.CenterHorizontally,
                    inkBottom = true,
                )
            }
        }
    }
}

/**
 * A link when [onClick] is set (an album's artist, a book's author; [linkLabel] says where it
 * leads), plain secondary text otherwise; its layout is its ink.
 */
@Composable
internal fun TitleLine(
    text: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    @StringRes linkLabel: Int = R.string.player_action_go_to_artist,
) {
    val style = MaterialTheme.typography.bodyLarge
    when {
        text == null -> SkeletonText(
            style,
            modifier,
            width = Sizes.SkeletonLine,
            alignment = Alignment.CenterHorizontally,
            inkTop = true,
            inkBottom = true,
        )

        onClick != null -> TextLink(
            text,
            stringResource(linkLabel),
            onClick,
            modifier,
            centred = true,
        )

        else -> Words(
            text,
            style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = modifier.textInk(style).inkCentre(text, style),
        )
    }
}

/**
 * The page's one focal action, a 56 dp ink circle that plays, on the page's middle; quiet glyphs
 * stand a group's distance from its edge on either side, as previous and next do on Now Playing:
 * [start] (shuffle) and [end] (the page's actions). The two sides take the same width whatever
 * their glyphs' ink, so the circle never leans toward the narrower one. The list's first ink comes
 * [Space.HeaderToList] under the circle; [listInk] is how far that ink sits inside the list's first item.
 */
@Composable
internal fun PageActions(
    playing: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    playLabel: String? = null,
    start: PageGlyph? = null,
    end: PageGlyph? = null,
    listInk: Dp = 0.dp,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = Space.Group, bottom = (Space.HeaderToList - listInk).coerceAtLeast(0.dp)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val glyphs = start != null || end != null
        val startGap = glyphGap(start ?: end)
        val endGap = glyphGap(end ?: start)
        val side = Sizes.TouchTarget + max(startGap, endGap)
        if (glyphs) {
            Box(Modifier.width(side).padding(end = startGap), contentAlignment = Alignment.CenterEnd) {
                PageGlyphSlot(start)
            }
        }
        PlayButton(playing, onPlay, size = Sizes.PagePlayButton, label = playLabel)
        if (glyphs) {
            Box(Modifier.width(side).padding(start = endGap), contentAlignment = Alignment.CenterStart) {
                PageGlyphSlot(end)
            }
        }
    }
}

/** A quiet action beside a page's play button; with [ring], inside a ring that shows how far a download is. */
internal class PageGlyph(
    @DrawableRes val icon: Int,
    val label: String,
    val ring: Ring? = null,
    val onClick: () -> Unit,
) {
    /** The ring fills with [progress], or turns while it is null. */
    class Ring(val progress: (() -> Float)?)
}

/** Shuffle starts the music, so it ticks as the play button does. */
@Composable
internal fun shuffleGlyph(onShuffle: () -> Unit): PageGlyph {
    val haptics = LocalHapticFeedback.current
    return PageGlyph(R.drawable.ic_shuffle, stringResource(R.string.shuffle)) {
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        onShuffle()
    }
}

/** The page's ⋮, for the item called [title]. */
@Composable
internal fun moreGlyph(title: String, onMore: () -> Unit) =
    PageGlyph(R.drawable.ic_more, stringResource(R.string.more_options_for, title), onClick = onMore)

@Composable
private fun PageGlyphSlot(glyph: PageGlyph?) {
    if (glyph == null) {
        Spacer(Modifier.size(Sizes.TouchTarget))
        return
    }
    val ring = glyph.ring
    if (ring != null) {
        RingAction(glyph.icon, glyph.label, glyph.onClick, ring.progress, tint = MaterialTheme.colorScheme.onSurface)
    } else {
        IconAction(glyph.icon, glyph.label, glyph.onClick, tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** So the glyph's ink, not its target, stands a group's distance from the circle. */
private fun glyphGap(glyph: PageGlyph?): Dp {
    val ink = glyph?.let { if (it.ring != null) Sizes.Icon else inkWidth(it.icon) } ?: Sizes.Icon
    return (Space.Group - (Sizes.TouchTarget - ink) / 2).coerceAtLeast(0.dp)
}

@Composable
internal fun PageUnavailable(
    @DrawableRes icon: Int,
    message: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MaterialScreen(modifier, bar = { PageTopBar(null, onBack) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            EmptyState(
                icon,
                message,
                Modifier.fillMaxWidth().weight(1f),
                actionLabel = stringResource(R.string.retry),
                onAction = onRetry,
            )
        }
    }
}
