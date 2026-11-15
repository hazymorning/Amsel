package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.theme.DISABLED_ALPHA
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.capHeight
import io.github.hazymorning.amsel.ui.theme.lineHeight
import io.github.hazymorning.amsel.ui.theme.rememberPress
import io.github.hazymorning.amsel.ui.theme.secondLine
import io.github.hazymorning.amsel.ui.theme.tabular
import kotlin.math.roundToInt

enum class RowLead {
    Cover,

    Glyph,

    /** A glyph centred in the 56 dp cover column, for lists that mix covers and glyphs. */
    GlyphUnderCover,

    None,
}

/** From the ring's edge to the words after it: a space and a little more, as after a symbol. */
private val RingGap = 6.dp

/**
 * The one row anatomy: a lead (cover, glyph or number) on the margin, the words 16 after it, and
 * an optional quiet trailing part. No menu: a tap opens the item, a long press is the shortcut to
 * its actions. [playing] puts the moss ring before the second line (a numbered lead shows its own);
 * [quiet] sets the title in the secondary tone (played). [band] fills the row's band (a row lifted
 * by a drag, a setting in its block); a finger shows it on any row. [titleLines] above 1 lets a
 * label wrap. A [selected] choice ([Role.RadioButton]) ends with a tick, whose room every choice
 * keeps.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    lead: RowLead = RowLead.None,
    twoLines: Boolean = lead == RowLead.Cover || subtitle != null,
    playing: Boolean = false,
    selected: Boolean = false,
    quiet: Boolean = false,
    band: Color? = null,
    /** The band's shape, and the press's: a row in a block of rows takes its place's corners. */
    bandShape: Shape = Shapes.Band,
    /** How far the row's content keeps from its band's sides: a cover less than words and glyphs. */
    inset: Dp = if (lead == RowLead.Cover || lead == RowLead.GlyphUnderCover) Space.BandInset else Space.BandText,
    /** Where the band's sides stand from the screen's; the content stays on the margin. */
    edge: Dp = Keyline.Margin - inset,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    role: Role = Role.Button,
    titleLines: Int = 1,
    subtitleLines: Int = 1,
    leadWidth: Dp = Sizes.Lead,
    /** Short lines (a speaker's name and what it plays) stay this narrow; the tick keeps to the end. */
    textMaxWidth: Dp = Dp.Unspecified,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    /** Before the second line when nothing plays there: a book's or episode's download ([DownloadMarkGlyph]). */
    mark: (@Composable () -> Unit)? = null,
) {
    val tap = if (onClick != null) {
        Modifier.combinedClickable(
            interactionSource = null,
            indication = rememberPress(bandShape),
            enabled = enabled,
            role = role,
            onClick = onClick,
            onLongClickLabel = onLongClick?.let { stringResource(R.string.more_options_for, title) },
            onLongClick = onLongClick,
        )
    } else {
        Modifier
    }
    val growth = rowGrowth(lead, twoLines)
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = edge, end = edge, bottom = growth)
            .then(if (band != null) Modifier.clip(bandShape).background(band) else Modifier)
            .then(if (role == Role.RadioButton) Modifier.semantics { this.selected = selected } else Modifier)
            .then(tap)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = inset)
            .heightIn(min = rowBand(lead, twoLines)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (lead) {
            RowLead.Cover -> {
                leading?.invoke()
                Spacer(Modifier.width(Space.Sibling))
            }

            RowLead.Glyph -> {
                Box(Modifier.width(leadWidth), contentAlignment = Alignment.CenterStart) { leading?.invoke() }
                Spacer(Modifier.width(Space.Sibling))
            }

            RowLead.GlyphUnderCover -> {
                Box(Modifier.width(Sizes.RowArt), contentAlignment = Alignment.Center) { leading?.invoke() }
                Spacer(Modifier.width(Space.Sibling))
            }

            RowLead.None -> Unit
        }
        RowText(
            title,
            subtitle,
            if (textMaxWidth == Dp.Unspecified) {
                Modifier.weight(1f)
            } else {
                Modifier.weight(1f).wrapContentWidth(Alignment.Start).widthIn(max = textMaxWidth)
            },
            twoLines = twoLines,
            playing = playing,
            ring = playing && (lead == RowLead.Cover || lead == RowLead.None),
            mark = mark,
            quiet = quiet,
            besideCover = lead == RowLead.Cover,
            titleLines = titleLines,
            subtitleLines = subtitleLines,
        )
        if (trailing != null) {
            Spacer(Modifier.width(Space.Sibling))
            trailing()
        } else if (role == Role.RadioButton) {
            Spacer(Modifier.width(Space.Sibling))
            // Laid out in every choice and drawn in the chosen one, so a name breaks alike chosen or not.
            InkGlyph(
                R.drawable.ic_check,
                Modifier.drawWithContent { if (selected) drawContent() },
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun rowHeight(lead: RowLead, twoLines: Boolean): Dp = when {
    lead == RowLead.Cover -> Sizes.RowHeight
    twoLines -> Sizes.TwoLineRowHeight
    else -> Sizes.CompactRowHeight
}

/** How far a row's first ink (the cover's top, or the title's cap top) sits below its top. */
@Composable
fun rowInkTop(lead: RowLead, twoLines: Boolean): Dp = rowGeometry(lead, twoLines).top

/** How far a row's last ink (the cover's bottom, or the last baseline) sits above its bottom. */
@Composable
fun rowInkBottom(lead: RowLead, twoLines: Boolean): Dp = rowGeometry(lead, twoLines).bottom

/** What a cover row adds under its cover when its text's lines need more than its 72 dp. */
@Composable
internal fun rowGrowth(lead: RowLead, twoLines: Boolean): Dp = rowGeometry(lead, twoLines).growth

/**
 * A row's band: [rowHeight], or the text's lines when large type needs more. Every cover row of a
 * list takes it, one line or two, so a list keeps one rhythm at every font size.
 */
@Composable
internal fun rowBand(lead: RowLead, twoLines: Boolean): Dp = rowGeometry(lead, twoLines).band

private class RowGeometry(val top: Dp, val bottom: Dp, val growth: Dp, val band: Dp, val capTop: Dp = top)

/**
 * Text rows centre their ink (cap top to last baseline) and grow by their line boxes. A cover row
 * keeps its cover, and the text centred on it, 8 under its top at every font size; when its lines
 * need more room it grows below its band, so every gap to a first cover holds.
 */
@Composable
private fun rowGeometry(lead: RowLead, twoLines: Boolean): RowGeometry {
    val type = MaterialTheme.typography
    return with(LocalDensity.current) {
        val ink = capHeight(type.bodyLarge) + if (twoLines) secondLine(type.bodyMedium) else 0.dp
        val lines = ink + linePad(type.bodyLarge, if (twoLines) type.bodyMedium else null) * 2 + Space.S2 * 2
        if (lead == RowLead.Cover) {
            val block = max(Sizes.RowArt, ink)
            val row = max(Sizes.RowHeight, block + Space.S2 * 2)
            val inset = (row - block) / 2
            val growth = (lines - row).coerceAtLeast(0.dp)
            RowGeometry(inset, inset + growth, growth, band = row, capTop = inset + (block - ink) / 2)
        } else {
            val band = max(rowHeight(lead, twoLines), lines)
            val inset = (band - ink) / 2
            RowGeometry(inset, inset, 0.dp, band)
        }
    }
}

/** What the line boxes add around the ink: room for accents above the capitals and descenders below. */
private fun Density.linePad(title: TextStyle, second: TextStyle?): Dp {
    val above = (lineHeight(title) - capHeight(title)) / 2
    val last = second ?: title
    return max(above, (lineHeight(last) - capHeight(last)) / 2)
}

/**
 * From a section heading's baseline to the top of the list's first row: a cover lands
 * [Space.HeadingToRow] under the baseline, as tiles do; a text row's capitals a group's distance
 * under it, so a pressed band never reaches the heading.
 */
@Composable
fun headerToRow(lead: RowLead, twoLines: Boolean): Dp {
    val row = rowGeometry(lead, twoLines)
    val offset = if (lead == RowLead.Cover) {
        max(Space.HeadingToRow - row.top, Space.Group - row.capTop)
    } else {
        Space.Group - row.top
    }
    return offset.coerceAtLeast(Space.S1)
}

/**
 * The lead column for a list numbered up to [largest]: 24 dp, or its figures, tabular, when they
 * need more. Measured once per list and given to every row.
 */
@Composable
fun numberLeadWidth(largest: Int): Dp {
    val widest = "0".repeat(largest.toString().length.coerceAtLeast(2))
    return max(Sizes.Lead, textInkWidth(widest, MaterialTheme.typography.bodyMedium.tabular()))
}

/**
 * The title, and the second line with its baseline one line under the title's. The layout is the
 * ink (cap top to last baseline) plus what the line boxes need around it, or the ink alone
 * [besideCover], where the cover sets the row's height; either way the ink is centred. [ring] puts
 * the playing ring before the second line, or before the title when there is none.
 */
@Composable
internal fun RowText(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    twoLines: Boolean = subtitle != null,
    playing: Boolean = false,
    ring: Boolean = false,
    mark: (@Composable () -> Unit)? = null,
    quiet: Boolean = false,
    besideCover: Boolean = false,
    titleLines: Int = 1,
    subtitleLines: Int = 1,
) {
    val titleStyle = MaterialTheme.typography.bodyLarge
    val nowPlaying = stringResource(R.string.player_now_playing)
    val colors = MaterialTheme.colorScheme
    val secondStyle = MaterialTheme.typography.bodyMedium
    val second = subtitle?.takeIf { it.isNotBlank() }
    RowLines(
        titleStyle,
        besideCover,
        modifier.then(if (playing) Modifier.semantics { stateDescription = nowPlaying } else Modifier),
    ) {
        val titleColor = if (quiet) colors.onSurfaceVariant else colors.onSurface
        val titleLine: @Composable () -> Unit = {
            Words(
                title,
                titleStyle,
                color = titleColor,
                maxLines = titleLines,
                tail = second.takeIf { !twoLines },
                modifier = Modifier.inkStart(title, titleStyle),
            )
        }
        // The playing ring stands where a download's mark would: what plays matters more.
        val before: (@Composable () -> Unit)? = if (ring) {
            { PlayingRing() }
        } else {
            mark
        }
        if (before != null && (second == null || !twoLines)) MarkBefore(titleStyle, before, titleLine) else titleLine()
        if (twoLines && second != null) {
            val line: @Composable () -> Unit = {
                Words(
                    second,
                    secondStyle,
                    color = colors.onSurfaceVariant,
                    maxLines = subtitleLines,
                    modifier = Modifier.inkStart(second, secondStyle),
                )
            }
            if (before != null) MarkBefore(secondStyle, before, line) else line()
        }
    }
}

/** The playing ring before the line ([MarkBefore]). */
@Composable
internal fun RingBefore(style: TextStyle, line: @Composable () -> Unit) = MarkBefore(style, { PlayingRing() }, line)

/** [mark] centred on [style]'s capitals, then the line; the line's baselines are the block's. */
@Composable
internal fun MarkBefore(style: TextStyle, mark: @Composable () -> Unit, line: @Composable () -> Unit) {
    val cap = with(LocalDensity.current) { capHeight(style).toPx() }
    Layout(content = {
        mark()
        line()
    }) { measurables, constraints ->
        val ring = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gap = RingGap.roundToPx()
        val text = measurables[1].measure(
            constraints.copy(minWidth = 0, maxWidth = (constraints.maxWidth - ring.width - gap).coerceAtLeast(0)),
        )
        val baseline = text[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: text.height
        val last = text[LastBaseline].takeIf { it != AlignmentLine.Unspecified } ?: baseline
        val ringTop = (baseline - cap / 2f - ring.height / 2f).roundToInt()
        val top = maxOf(0, -ringTop)
        val height = maxOf(text.height + top, ringTop + top + ring.height)
        layout(
            maxOf(ring.width + gap + text.width, constraints.minWidth),
            height,
            mapOf(FirstBaseline to baseline + top, LastBaseline to last + top),
        ) {
            ring.placeRelative(0, ringTop + top)
            text.placeRelative(ring.width + gap, top)
        }
    }
}

/**
 * Lays out [RowText]'s lines, and the skeleton bars that stand in for them, alike. [inset] keeps
 * room over and under them; the dock's larger lines take less, so they stay within its cover's height.
 */
@Composable
internal fun RowLines(
    titleStyle: TextStyle,
    besideCover: Boolean,
    modifier: Modifier = Modifier,
    secondStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    inset: Dp = Space.S2,
    lines: @Composable () -> Unit,
) {
    Layout(lines, modifier.padding(vertical = inset)) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0)
        val title = measurables[0].measure(loose)
        val second = measurables.getOrNull(1)?.measure(loose)
        val titleLine = lineHeight(titleStyle).toPx()
        val titleCap = capHeight(titleStyle).toPx()
        val firstBaseline = title.line(FirstBaseline, (titleLine + titleCap) / 2)
        val titleBaseline = title.line(LastBaseline, title.height - (titleLine - titleCap) / 2)
        val capTop = firstBaseline - titleCap.roundToInt()
        val secondHeight = lineHeight(secondStyle).toPx()
        val secondBaseline = titleBaseline + secondLine(secondStyle).roundToPx()
        // A second text that wraps (a setting's description) adds its further lines under its first.
        val secondSpan = second?.let { it.line(LastBaseline, 0f) - it.line(FirstBaseline, 0f) } ?: 0
        val lastBaseline = if (second == null) titleBaseline else secondBaseline + secondSpan
        val below = if (second == null) {
            title.height - titleBaseline
        } else {
            second.height - second.line(LastBaseline, (secondHeight + capHeight(secondStyle).toPx()) / 2)
        }
        val pad = if (besideCover) 0 else maxOf(capTop, below)
        val height = maxOf(lastBaseline - capTop + pad * 2, constraints.minHeight)
        val top = (height - (lastBaseline - capTop)) / 2 - capTop
        layout(maxOf(title.width, second?.width ?: 0, constraints.minWidth), height) {
            title.placeRelative(0, top)
            second?.placeRelative(0, top + secondBaseline - second.line(FirstBaseline, 0f))
        }
    }
}

private fun Placeable.line(line: AlignmentLine, fallback: Float): Int =
    get(line).takeIf { it != AlignmentLine.Unspecified } ?: fallback.roundToInt()
