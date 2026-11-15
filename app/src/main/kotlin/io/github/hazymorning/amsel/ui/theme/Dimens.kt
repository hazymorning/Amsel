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

/* Values derive from the margin, the 4 dp grid and the radius scale; letters, not boxes, line up. */

object Keyline {
    /** The one side margin: every left edge in the app sits on it, and every right edge on its mirror. */
    val Margin = 24.dp
}

/** How far content dissolves where it meets an edge, never cut. */
object Fade {
    /** Under a bar, a row of chips past the screen, search results under the field. */
    val Long = 24.dp

    /** A scrolled list's ends; the status strip, below the status bar. */
    val Short = 16.dp
}

/** Distances: 4, 8, 12, 16, 24, 32, 48, 64. Gaps run between letters and edges. */
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

    /** A title's baseline to the next line's baseline adds this to that line's own height. */
    val Line = 4.dp

    /** Parts of one thing: a cover and its caption, a glyph and its label. */
    val Part = 8.dp

    /** A cover's edge to its caption's capitals. */
    val Caption = 14.dp

    /** A small label's baseline (a settings group's name, the library's order) to what it labels. */
    val LabelToContent = 18.dp

    /** Tiles side by side, in a shelf or a grid. */
    val Tiles = 16.dp

    /** Small covers side by side in their shelf, and Home's cards. */
    val SmallTiles = 12.dp

    /** Siblings: a row's cover and its text, a message and its action. */
    val Sibling = 16.dp

    /** Groups inside a screen. */
    val Group = 24.dp

    /** From a group's last letters to the list under it. */
    val BeforeList = 32.dp

    /** From a page's header (its play button, or its last letters) to the first ink of its list. */
    val HeaderToList = 48.dp

    /** From a sheet's heading baseline to its content's first edge. */
    val HeaderToContent = 16.dp

    /** From a section heading's baseline to its row of covers or list. */
    val HeadingToRow = 22.5.dp

    /** From one section's last letters to the next heading's capitals. */
    val SectionToHeader = 48.dp

    /** A grid's caption baseline to the next row of covers. */
    val TileRows = 32.dp

    /** The last letters of a scrolling screen to the dock: clear of the dock's fade, which is as high. */
    val ListEnd = 40.dp

    /** How far a row's cover sits inside its pressed band, so the band's corners nest around the cover's. */
    val BandInset = 8.dp

    /** How far a row's words and glyphs keep from its band's sides, as from the sides of any filled shape. */
    val BandText = 16.dp
}

/** Groups stand apart by at least this many times the largest gap inside either. */
private const val PROXIMITY = 1.25f

/**
 * [Space.Group] after text that can wrap. At large font sizes the gap between its own lines
 * (baseline to the next line's capitals) grows, so the group keeps 1.25 times it.
 */
fun Density.groupAfter(style: TextStyle): Dp = max(Space.Group, (lineHeight(style) - capHeight(style)) * PROXIMITY)

/**
 * Text grows with Android's font size while covers and gaps would stay put; vertical gaps and fixed
 * heights grow with it, up to a third, so the proportions hold.
 */
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

    /** The dock's cover, on its card. */
    val DockArt = 48.dp

    /** The speaker's pill in Now Playing: a step up from the quiet pills. */
    val SpeakerPill = 44.dp

    /** A book's or an episode's progress under its cover: as thick as Now Playing's line ([SliderTrack]). */
    val ProgressLine get() = SliderTrack

    /** Now Playing's play: the one filled circle there. */
    val PlayButton = 72.dp

    /** A page's play: its one focal action. */
    val PagePlayButton = 56.dp

    /** The dot under a mode that is on (shuffle), and its gap below the glyph's ink. */
    val ToggleDot = 4.dp
    val ToggleDotGap = 4.dp

    val ButtonHeight = 48.dp

    /** Pills and chips: a speaker, the volume, a choice; their words 16 from their ends. */
    val PillHeight = 36.dp
    val ChipHeight = PillHeight
    val FieldHeight = 48.dp

    val SliderTrack = 4.dp

    /** Every slider's solid thumb, and while a finger holds it. */
    val Thumb = 12.dp
    val ThumbHeld = 20.dp

    /** The playing ring beside an item in a list. */
    val Ring = 8.dp

    /** The ring's line, a fifth of its width, as the blackbird's eye. */
    val RingStroke = 1.5.dp

    val Measure = 280.dp

    val Hairline = 1.dp

    val SpinnerStroke = 1.5.dp

    /** A skeleton bar for a short line of its own: a section heading, a page's artist. */
    val SkeletonLine = 144.dp

    /** A shelf's tile: two from the margin, with a third's width left for the next. */
    fun tile(screenWidth: Dp): Dp = (screenWidth - Keyline.Margin - Space.Tiles * 2) * 3 / 7

    /** A big cover in a row: two between the margins, as a grid's two columns are. */
    fun bigTile(screenWidth: Dp): Dp = (screenWidth - Keyline.Margin * 2 - Space.Tiles) / 2

    /** A small cover in a row, [Space.SmallTiles] apart: three, with a third's width left for the next. */
    fun smallTile(screenWidth: Dp): Dp = (screenWidth - Keyline.Margin - Space.SmallTiles * 3) * 3 / 10

    /** A page's column, which its title wraps in: the content width, as Now Playing's. */
    fun pageColumn(screenWidth: Dp): Dp = (screenWidth - Keyline.Margin * 2).coerceAtMost(PageColumnMax)

    private val PageColumnMax = 480.dp

    /** A page's cover: three quarters of its column, as a book's title page. */
    fun pageCover(screenWidth: Dp): Dp = pageColumn(screenWidth) * 3 / 4

    /** An album grid takes as many columns as keep every tile at least this wide. */
    val GridTileMin = 144.dp
}

/** A small scale; a container holding a shape inset by p has that shape's radius plus p. */
object Radius {
    /** A small cover: a row's, the dock's, a tile's. */
    val Thumb = 8.dp

    /** Cards, fields, the message bar. */
    val Tile = 12.dp

    /** The big artwork: Now Playing, a page's cover; sheets' top corners. */
    val Large = 16.dp

    /** A cover's corners. */
    fun cover(side: Dp): Dp = if (side < 200.dp) Thumb else Large

    /** How far in the words under a cover [side] wide start ([under]). */
    fun caption(side: Dp): Dp = under(cover(side))

    /**
     * Words under corners of [radius] start half of it in: flush, a rounded corner makes them look
     * as if they stuck out; the whole radius in, they look indented.
     */
    fun under(radius: Dp): Dp = radius / 2
}

object Shapes {
    fun cover(side: Dp): Shape = SmoothShape(Radius.cover(side))

    val Thumb: Shape = SmoothShape(Radius.Thumb)

    val Tile: Shape = SmoothShape(Radius.Tile)

    /** A row's pressed band: its corners nest around a row cover inset [Space.BandInset]. */
    val Band: Shape = SmoothShape(Radius.Thumb + Space.BandInset)

    /**
     * Chips and buttons that carry a label: fully round ends, also for one line of the largest text;
     * a label that wraps keeps its corners.
     */
    val Pill: Shape = RoundedCornerShape(HalfUpTo(32.dp))

    val Circle: Shape = CircleShape

    val Sheet: Shape = SmoothShape.top(Radius.Large)
}

/** Half the shape's height, but never more than [max]. */
private class HalfUpTo(private val max: Dp) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float =
        minOf(shapeSize.height / 2, with(density) { max.toPx() })
}
