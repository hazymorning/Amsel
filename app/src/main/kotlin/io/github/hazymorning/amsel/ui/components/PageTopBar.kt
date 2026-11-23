package io.github.hazymorning.amsel.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.capHeight
import io.github.hazymorning.amsel.ui.theme.controls
import kotlin.math.min

/**
 * What the dock covers at the foot of the screen; scrolling content keeps its last items clear of it.
 * The shell provides it as state, read where it is laid out: it moves with the dock's animation.
 */
val LocalDockInset = staticCompositionLocalOf<State<Dp>> { mutableStateOf(0.dp) }

/**
 * A screen whose content scrolls up under the status bar, where a strip of the page's own paper
 * covers it ([statusBarBackground]) once content runs under it. With a [bar] the strip also lies behind that
 * slim bar, and the content starts under it. Given [barShown], the content starts under the status
 * bar instead and the bar comes over it, folded away while false: for a screen whose own big title is
 * still in view. [content] gets the room the strip, the bar and the dock take, to keep its items
 * clear of them.
 */
@Composable
fun MaterialScreen(
    modifier: Modifier = Modifier,
    bar: (@Composable BoxScope.() -> Unit)? = null,
    barShown: Boolean? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    // The status bar and a cutout, not safeDrawing: the keyboard never reaches the top, and reading it
    // here would recompose the screen on every frame the keyboard moves.
    val top = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val status = top.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
    val barOverlaps = barShown != null
    // Read only in layout and drawing, so the fold does not recompose the screen.
    val shown = animateFloatAsState(if (bar != null && barShown != false) 1f else 0f, Motion.effects(), label = "bar")
    val reserved = if (bar != null && !barOverlaps) Sizes.TopBarHeight else 0.dp
    val dock = LocalDockInset.current
    val padding = remember(status, reserved, dock) { ContentPadding(status + reserved, dock) }
    val edge = remember { ScrollEdgeState() }
    Box(modifier.fillMaxSize()) {
        // In landscape a side navigation bar or a cutout would cover the ends of rows.
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(top.only(WindowInsetsSides.Horizontal)),
        ) {
            CompositionLocalProvider(LocalTopEdge provides edge) { content(padding) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val room = status + if (barOverlaps) Sizes.TopBarHeight * shown.value else reserved
                    val height = constraints.constrainHeight(room.roundToPx())
                    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .statusBarBackground(edge),
        )
        if (bar != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(top.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .height(Sizes.TopBarHeight)
                    .graphicsLayer { alpha = if (barOverlaps) shown.value else 1f },
                content = bar,
            )
        }
    }
}

private val TabTitleCapTop = Space.Sibling

/** The title's own baseline, so an action beside it never sets the header's frame. */
private val TitleBaseline = HorizontalAlignmentLine(::min)

/**
 * A tab's title in the Display style, its capitals 16 under the top (the status bar's edge in a
 * [MaterialScreen]). Actions sit on the right margin, centred on the capitals.
 */
@Composable
fun TabHeader(
    title: String,
    modifier: Modifier = Modifier,
    below: Dp = Space.Sibling,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val style = MaterialTheme.typography.displaySmall
    val cap = with(LocalDensity.current) { capHeight(style) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Keyline.Margin)
            .baselineFrame(top = TabTitleCapTop + cap, bottom = below, line = TitleBaseline),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Words(
            title,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .capToBaseline(style)
                .trimStartBearing(title, style)
                .providesBaseline(TitleBaseline)
                .semantics { heading() },
        )
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.controls) { actions() }
    }
}

/**
 * The slim bar on the status strip: back on the left margin, actions on the right, and the title
 * centred between them in the body size. [showTitle] fades the title in once the page's own title
 * has scrolled away. A [quiet] title is a line about the screen rather than its name, smaller and in
 * the secondary tone, and no heading. [inset] sets the glyphs that far in from the margins, where a
 * screen's words stand in from them (Now Playing); [titleInset] keeps the title clear of more than
 * one action, and [shorterTitle] stands in where the title does not fit its line. Actions wider
 * than a glyph (a word) narrow the title's room to what they leave ([barTitleBounds]).
 */
@Composable
fun PageTopBar(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    quiet: Boolean = false,
    @DrawableRes backIcon: Int = R.drawable.ic_back,
    backLabel: String = stringResource(R.string.back),
    inset: Dp = 0.dp,
    titleInset: Dp = BarTitleInset,
    shorterTitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val titleAlpha by animateFloatAsState(if (showTitle) 1f else 0f, Motion.effects(), label = "title")
    val colors = MaterialTheme.colorScheme
    val style = if (quiet) quietTitle() else MaterialTheme.typography.titleMedium
    val measurer = rememberTextMeasurer()
    Layout(
        content = {
            Box {
                if (onBack != null) {
                    IconAction(backIcon, backLabel, onBack, edge = GlyphEdge.Start, tint = colors.controls)
                }
            }
            Box {
                if (title != null) {
                    Words(
                        title,
                        style = style,
                        color = if (quiet) colors.onSurfaceVariant else colors.onSurface,
                        textAlign = TextAlign.Center,
                        shorter = shorterTitle,
                        modifier = Modifier
                            .graphicsLayer { alpha = titleAlpha }
                            .then(
                                when {
                                    !showTitle -> Modifier.clearAndSetSemantics {}
                                    quiet -> Modifier
                                    else -> Modifier.semantics { heading() }
                                },
                            ),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalContentColor provides colors.controls) { actions() }
            }
        },
        modifier = modifier.fillMaxWidth().height(Sizes.TopBarHeight),
    ) { (back, words, trailing), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val edge = (Keyline.Margin + inset).roundToPx()
        val gap = Space.Sibling.roundToPx()
        val lead = back.measure(loose)
        val acts = trailing.measure(loose)
        val shortest = shorterTitle ?: title.orEmpty()
        val room = barTitleBounds(
            width = width,
            inset = titleInset.roundToPx(),
            taken = Keyline.Margin.roundToPx() + acts.width + gap,
            from = if (lead.width > 0) edge + lead.width + gap else edge,
            needed = measurer.measure(shortest, style, softWrap = false, maxLines = 1).size.width,
        )
        val text = words.measure(loose.copy(maxWidth = room.width))
        layout(width, height) {
            lead.placeRelative(edge, (height - lead.height) / 2)
            text.placeRelative(room.start, (height - text.height) / 2)
            acts.placeRelative(width - edge - acts.width, (height - acts.height) / 2)
        }
    }
}

/** A quiet bar title (Now Playing's "from Blue Hour"): a line about the screen, at the captions' size. */
@Composable
private fun quietTitle() = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Normal)

/** A centred bar title keeps clear of a glyph's target on either side. */
internal val BarTitleInset = Keyline.Margin + Sizes.TouchTarget + Space.S2

/**
 * These paddings with [horizontal] more on either side, [top] and [bottom] more, each read only when
 * asked for: a screen's room holds the dock, whose height animates, and read in composition it would
 * recompose the screen on every frame.
 */
fun PaddingValues.plus(horizontal: Dp = 0.dp, top: Dp = 0.dp, bottom: Dp = 0.dp): PaddingValues =
    AddedPadding(this, horizontal, top, bottom, baseTop = true)

/** As [plus], for a part of a screen that starts below its top: [top] in place of this padding's own. */
fun PaddingValues.withTop(top: Dp, bottom: Dp = 0.dp): PaddingValues =
    AddedPadding(this, 0.dp, top, bottom, baseTop = false)

private class AddedPadding(
    private val base: PaddingValues,
    private val horizontal: Dp,
    private val top: Dp,
    private val bottom: Dp,
    private val baseTop: Boolean,
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        base.calculateLeftPadding(layoutDirection) + horizontal

    override fun calculateTopPadding(): Dp = (if (baseTop) base.calculateTopPadding() else 0.dp) + top

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        base.calculateRightPadding(layoutDirection) + horizontal

    override fun calculateBottomPadding(): Dp = base.calculateBottomPadding() + bottom
}

/** A screen's room: [top] as given, the dock's at the bottom, read only when it is asked for. */
private class ContentPadding(private val top: Dp, private val dock: State<Dp>) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp

    override fun calculateTopPadding(): Dp = top

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp

    override fun calculateBottomPadding(): Dp = dock.value
}
