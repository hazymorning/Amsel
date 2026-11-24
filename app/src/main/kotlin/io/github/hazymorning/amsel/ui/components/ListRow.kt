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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

enum class RowLead {
    Cover,

    Glyph,

    /** A glyph centred in the 56 dp cover column, for lists that mix covers and glyphs. */
    GlyphUnderCover,

    None,
}

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
            // Its middle stands where a pill button's round end has its centre, so it lines up under one.
            AlignedGlyph(
                R.drawable.ic_check,
                Modifier
                    .padding(end = Sizes.ButtonHeight / 2 - glyphWidth(R.drawable.ic_check) / 2)
                    .drawWithContent { if (selected) drawContent() },
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
fun rowGlyphTop(lead: RowLead, twoLines: Boolean): Dp = rowGeometry(lead, twoLines).top

/** How far a row's last ink (the cover's bottom, or the last baseline) sits above its bottom. */
@Composable
fun rowGlyphBottom(lead: RowLead, twoLines: Boolean): Dp = rowGeometry(lead, twoLines).bottom

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
    return max(Sizes.Lead, textGlyphWidth(widest, MaterialTheme.typography.bodyMedium.tabular()))
}
