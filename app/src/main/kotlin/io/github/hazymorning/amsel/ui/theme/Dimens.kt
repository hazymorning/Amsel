package io.github.hazymorning.amsel.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max

// Gaps are measured to the letters (cap tops and baselines), not to text boxes.

object Keyline {
    /** The only side margin: every left edge sits on it, every right edge on its mirror. */
    val Margin = 24.dp
}

object Fade {
    val Long = 24.dp

    val Short = 16.dp
}

object Space {
    val S1 = 4.dp
    val S2 = 8.dp
    val S3 = 12.dp
    val S4 = 16.dp
    val S6 = 24.dp
    val S8 = 32.dp
    val S10 = 40.dp
    val S12 = 48.dp
    val S16 = 64.dp

    /** Added to the next line's own height for the step from a title's baseline to that line's. */
    val Line = 4.dp

    val Part = 8.dp

    /** A cover's edge to its caption's capitals. */
    val Caption = 14.dp

    /** A small label's baseline to what it labels. */
    val LabelToContent = 18.dp

    val Tiles = 16.dp

    val SmallTiles = 12.dp

    val Sibling = 16.dp

    val Group = 24.dp

    /** From a group's last letters to the list under it. */
    val BeforeList = 32.dp

    /** From a page's header (its play button, or its last letters) to its list's first visible content. */
    val HeaderToList = 48.dp

    /**
     * From a heading's baseline to its content's top (a shape's edge, a sentence's capitals); never less
     * (docs/DESIGN.md, principle 11).
     */
    val HeaderToContent = 24.dp

    /**
     * From a sheet title's baseline to its first row's band, whose inset puts a cover's top
     * [HeaderToContent] under the baseline.
     */
    val HeaderToRows = 16.dp

    /**
     * A sheet's top edge to its first content (a title's capitals, a cover's top), and its last content
     * to its bottom edge. More than the side margin, which looks tight over a bold title.
     */
    val SheetEdge = 32.dp

    /** From a section heading's baseline to its row of covers or list. */
    val HeadingToRow = 24.dp

    /** From one section's last letters to the next heading's capitals. */
    val SectionToHeader = 48.dp

    /** A grid's caption baseline to the next row of covers. */
    val TileRows = 32.dp

    /** From a scrolling screen's last letters to the dock, clear of its shadow. */
    val ListEnd = 40.dp

    /** A row cover's inset in its pressed band, so the band's corners nest around the cover's. */
    val BandInset = 8.dp

    /** A row's words and glyphs from its band's sides, as inside any filled shape. */
    val BandText = 16.dp
}

/** Groups stand apart by at least this many times the largest gap inside either. */
private const val PROXIMITY = 1.25f

/**
 * [Space.Group] after text that can wrap: at large font sizes its own line gap (baseline to the next
 * line's capitals) grows, and the gap after it stays [PROXIMITY] times that.
 */
fun Density.groupAfter(style: TextStyle): Dp = max(Space.Group, (lineHeight(style) - capHeight(style)) * PROXIMITY)

/** Vertical gaps and fixed heights grow with the font scale up to [MAX_SPACE_SCALE], so proportions hold. */
fun Density.spaced(gap: Dp): Dp = gap * fontScale.coerceIn(1f, MAX_SPACE_SCALE)

private const val MAX_SPACE_SCALE = 1.3f

object Sizes {
    val TouchTarget = 48.dp

    val IconSmall = 16.dp
    val Icon = 24.dp
    val IconLarge = 32.dp

    val RowHeight = 72.dp
    val RowArt = 56.dp

    /** The column before a row's words: track numbers, row glyphs. */
    val Lead = 24.dp

    val CompactRowHeight = 56.dp
    val TwoLineRowHeight = 64.dp

    val TopBarHeight = 56.dp

    val DockArt = 48.dp

    val SpeakerPill = 44.dp

    val ProgressLine get() = SliderTrack

    val PlayButton = 72.dp

    val PagePlayButton = 56.dp

    /** The dot under a mode that is on, and its gap below the glyph's drawn bottom. */
    val ToggleDot = 4.dp
    val ToggleDotGap = 4.dp

    val ButtonHeight = 48.dp

    val PillHeight = 36.dp
    val ChipHeight = PillHeight
    val FieldHeight = 48.dp

    val SliderTrack = 4.dp

    val Thumb = 12.dp
    val ThumbHeld = 20.dp

    val Measure = 280.dp

    val Hairline = 1.dp

    val SpinnerStroke = 1.5.dp

    val SkeletonLine = 144.dp

    /** Two tiles and a third of the next fit after the margin. */
    fun tile(screenWidth: Dp): Dp = (screenWidth - Keyline.Margin - Space.Tiles * 2) * 3 / 7

    fun bigTile(screenWidth: Dp): Dp = (screenWidth - Keyline.Margin * 2 - Space.Tiles) / 2

    /** Three tiles and a third of the next fit after the margin. */
    fun smallTile(screenWidth: Dp): Dp = (screenWidth - Keyline.Margin - Space.SmallTiles * 3) * 3 / 10

    fun pageColumn(screenWidth: Dp): Dp = (screenWidth - Keyline.Margin * 2).coerceAtMost(PageColumnMax)

    private val PageColumnMax = 480.dp

    fun pageCover(screenWidth: Dp): Dp = pageColumn(screenWidth) * 3 / 4

    val GridTileMin = 144.dp
}

/** A container around a shape inset by p takes that shape's radius plus p. */
object Radius {
    val Thumb = 8.dp

    val Tile = 12.dp

    val Large = 16.dp

    fun cover(side: Dp): Dp = if (side < 200.dp) Thumb else Large

    fun caption(side: Dp): Dp = under(cover(side))

    /** Half the radius: flush, words look as if they stick out past a rounded corner; a full radius in, indented. */
    fun under(radius: Dp): Dp = radius / 2
}

object Shapes {
    fun cover(side: Dp): Shape = SmoothShape(Radius.cover(side))

    val Thumb: Shape = SmoothShape(Radius.Thumb)

    val Tile: Shape = SmoothShape(Radius.Tile)

    val Band: Shape = SmoothShape(Radius.Thumb + Space.BandInset)

    /** Fully round ends up to one line of the largest text; a label that wraps keeps capped corners. */
    val Pill: Shape = RoundedCornerShape(CappedHalfCorner(32.dp))

    val Circle: Shape = CircleShape

    val Sheet: Shape = SmoothShape.top(Radius.Large)
}

private class CappedHalfCorner(private val max: Dp) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float =
        minOf(shapeSize.height / 2, with(density) { max.toPx() })
}
