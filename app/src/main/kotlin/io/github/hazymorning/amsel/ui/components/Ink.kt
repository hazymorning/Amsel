package io.github.hazymorning.amsel.ui.components

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.theme.DISABLED_ALPHA
import io.github.hazymorning.amsel.ui.theme.Radius
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.capHeight
import io.github.hazymorning.amsel.ui.theme.rememberPress
import kotlin.math.abs
import kotlin.math.roundToInt

/** A glyph's ink on its 256-unit grid; [mirrored] glyphs flip in right-to-left layouts. */
@Immutable
internal data class GlyphInk(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val mirrored: Boolean,
)

private const val GRID = 256f

/** A glyph's other cuts beside its Regular one ([IconCuts]); [heavy] only where it is drawn at 12 dp. */
@Immutable
internal class Cuts(@DrawableRes val bold: Int, @DrawableRes val light: Int, @DrawableRes val heavy: Int? = null)

/** Phosphor's line in each cut, in units of its 256 grid: Regular, Bold, Light and the heavy cut. */
private const val REGULAR_LINE = 16f
private const val BOLD_LINE = 24f
private const val LIGHT_LINE = 12f
private const val HEAVY_LINE = 32f

/** Every glyph's line, whatever its size. */
private val LINE = 1.5.dp

/**
 * The cut of [icon] whose line is nearest [LINE] at [size]: Bold at 16 dp, Regular at 24, Light at
 * 32. A glyph without cuts (filled, or three dots) is drawn as it is.
 */
@DrawableRes
internal fun weighted(@DrawableRes icon: Int, size: Dp): Int {
    val cuts = IconCuts[icon] ?: return icon

    // Drawn for every glyph on every frame it composes: a plain comparison, nothing allocated.
    fun miss(line: Float) = abs((size * (line / GRID) - LINE).value)
    var best = icon
    var nearest = miss(REGULAR_LINE)
    if (miss(BOLD_LINE) < nearest) {
        best = cuts.bold
        nearest = miss(BOLD_LINE)
    }
    if (miss(LIGHT_LINE) < nearest) {
        best = cuts.light
        nearest = miss(LIGHT_LINE)
    }
    val heavy = cuts.heavy
    if (heavy != null && miss(HEAVY_LINE) < nearest) best = heavy
    return best
}

internal fun inkOf(@DrawableRes icon: Int): GlyphInk = IconInk[icon] ?: GlyphInk(0f, 0f, GRID, GRID, false)

fun inkWidth(@DrawableRes icon: Int, size: Dp = Sizes.Icon): Dp =
    inkOf(weighted(icon, size)).let { size * ((it.right - it.left) / GRID) }

fun inkHeight(@DrawableRes icon: Int, size: Dp = Sizes.Icon): Dp =
    inkOf(weighted(icon, size)).let { size * ((it.bottom - it.top) / GRID) }

/** How far below the top of its [size] box a glyph's ink starts. */
fun inkTop(@DrawableRes icon: Int, size: Dp = Sizes.Icon): Dp = size * (inkOf(weighted(icon, size)).top / GRID)

/**
 * How large a glyph is beside words: as large as they are set, so it stands among them as a letter
 * does, a little taller than their capitals, and grows with them when the type is set larger.
 */
@Composable
fun wordGlyph(style: TextStyle): Dp = with(LocalDensity.current) { style.fontSize.toDp() }

/** A caret after words, which only says there is more: a step smaller than a glyph beside them. */
@Composable
fun wordCaret(style: TextStyle): Dp = wordGlyph(style) * CARET

private const val CARET = 0.82f

/** Which edge of a glyph's ink lines up with the edge of its layout. */
enum class InkEdge { Start, End, Centre }

/**
 * A glyph whose layout is its ink from side to side, and with [inkHeight] also from top to bottom.
 * Otherwise it keeps the [size] box's height with the ink centred in it, so glyphs in a row centre
 * alike and a glyph centred on text centres its ink there, wherever the drawing sits in its box.
 */
@Composable
fun InkGlyph(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = Sizes.Icon,
    contentDescription: String? = null,
    inkHeight: Boolean = false,
) {
    val drawn = weighted(icon, size)
    val ink = inkOf(drawn)
    Icon(
        painterResource(drawn),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
            .layout { measurable, constraints ->
                val box = size.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(box, box))
                val shift = inkShift(box, ink, inkHeight)
                val width = (box * (ink.right - ink.left) / GRID).roundToInt()
                val height = if (inkHeight) (box * (ink.bottom - ink.top) / GRID).roundToInt() else box
                layout(width, height) {
                    // A mirrored glyph flips with the layout; a plain one keeps its drawing.
                    if (ink.mirrored) placeable.placeRelative(-shift) else placeable.place(-shift)
                }
            }
            .size(size),
    )
}

/**
 * How far an [InkGlyph] of [box] pixels moves its drawing so its ink starts at the layout's start,
 * and with [inkHeight] at its top; otherwise the ink centres in the box's height.
 */
private fun inkShift(box: Int, ink: GlyphInk, inkHeight: Boolean) = IntOffset(
    (box * ink.left / GRID).roundToInt(),
    if (inkHeight) {
        (box * ink.top / GRID).roundToInt()
    } else {
        (box * ((ink.top + ink.bottom) / 2 - GRID / 2) / GRID).roundToInt()
    },
)

/**
 * Where the point ([x], [y]) of [icon]'s 256 grid lands in its [InkGlyph] of [size], in pixels from
 * the layout's top left: for a mark set on the glyph's own lines, as the speaker's dot on its
 * outline. Not for a glyph that mirrors.
 */
internal fun Density.inkPoint(
    @DrawableRes icon: Int,
    size: Dp,
    x: Float,
    y: Float,
    inkHeight: Boolean = false,
): Offset {
    val box = size.roundToPx()
    val shift = inkShift(box, inkOf(weighted(icon, size)), inkHeight)
    return Offset(box * x / GRID - shift.x, box * y / GRID - shift.y)
}

/**
 * A 48 dp target centred on the glyph's ink. With [edge] Start or End the layout stops at that edge
 * of the ink, so the glyph lands on its keyline and the target overhangs into the margin. Dimmed
 * while not [enabled].
 */
@Composable
fun IconAction(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    edge: InkEdge = InkEdge.Centre,
    tint: Color = LocalContentColor.current,
    size: Dp = Sizes.Icon,
    enabled: Boolean = true,
) {
    val spare = endReach(icon, size)
    Box(
        modifier
            .overhang(
                start = if (edge == InkEdge.Start) spare else 0.dp,
                end = if (edge == InkEdge.End) spare else 0.dp,
            )
            .size(Sizes.TouchTarget)
            .clickable(
                interactionSource = null,
                indication = rememberPress(Shapes.Circle),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        contentAlignment = Alignment.Center,
    ) {
        InkGlyph(icon, tint = tint, size = size, contentDescription = contentDescription)
    }
}

/**
 * The glyph a row is dragged by, its ink on the row's end keyline, in a 48 dp target; [modifier]
 * carries the drag. Only a glyph, not a button: TalkBack moves rows through the row's own actions.
 */
@Composable
fun DragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier.overhang(end = endReach(R.drawable.ic_drag)).size(Sizes.TouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        InkGlyph(R.drawable.ic_drag, tint = MaterialTheme.colorScheme.outline, contentDescription = null)
    }
}

fun endReach(@DrawableRes icon: Int, size: Dp = Sizes.Icon): Dp = (Sizes.TouchTarget - inkWidth(icon, size)) / 2

/** Words under a cover as wide as they are, a little in from its edge ([Radius.caption]). */
fun Modifier.underCover(): Modifier = layout { measurable, constraints ->
    val inset = if (constraints.hasBoundedWidth) Radius.caption(constraints.maxWidth.toDp()).roundToPx() else 0
    val placeable = measurable.measure(constraints.offset(horizontal = -inset))
    layout(placeable.width + inset, placeable.height) { placeable.placeRelative(inset, 0) }
}

/** Draws and takes touches beyond its layout by these amounts. */
fun Modifier.overhang(start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp): Modifier =
    layout { measurable, constraints ->
        val s = start.roundToPx()
        val t = top.roundToPx()
        val e = end.roundToPx()
        val b = bottom.roundToPx()
        val placeable = measurable.measure(constraints.offset(s + e, t + b))
        val width = (placeable.width - s - e).coerceAtLeast(0)
        val height = (placeable.height - t - b).coerceAtLeast(0)
        layout(width, height) {
            val x = if (layoutDirection == LayoutDirection.Rtl) -e else -s
            placeable.place(x, -t)
        }
    }

/**
 * Places a block so its first baseline sits [top] below the layout's top and the layout ends [bottom]
 * below its last. With [line], that one line stands for both (a title among actions that have text).
 */
fun Modifier.baselineFrame(top: Dp, bottom: Dp, line: HorizontalAlignmentLine? = null): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0))
        val first = placeable[line ?: FirstBaseline]
        val last = placeable[line ?: LastBaseline]
        if (first == AlignmentLine.Unspecified || last == AlignmentLine.Unspecified) {
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val y = top.roundToPx() - first
        layout(placeable.width, (y + last + bottom.roundToPx()).coerceAtLeast(0)) { placeable.place(0, y) }
    }

/** Offers this text's first baseline as [line] too, for a parent that must tell it from its siblings'. */
fun Modifier.providesBaseline(line: HorizontalAlignmentLine): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val lines = mapOf<AlignmentLine, Int>(line to placeable[FirstBaseline])
        .filterValues { it != AlignmentLine.Unspecified }
    layout(placeable.width, placeable.height, lines) { placeable.place(0, 0) }
}

/** A paint that sets [style] as Text does, to read its ink from the font; null while the font loads. */
@Composable
private fun rememberInkPaint(style: TextStyle): Paint? {
    val resolver = LocalFontFamilyResolver.current
    val density = LocalDensity.current
    return remember(style, density, resolver) {
        val typeface = resolver.resolve(
            style.fontFamily,
            style.fontWeight ?: FontWeight.Normal,
            style.fontStyle ?: FontStyle.Normal,
            FontSynthesis.None,
        ).value as? Typeface ?: return@remember null
        Paint().apply {
            this.typeface = typeface
            textSize = with(density) { style.fontSize.toPx() }
            if (style.letterSpacing.isEm) {
                letterSpacing = style.letterSpacing.value
            } else if (style.letterSpacing.isSp && style.fontSize.isSp) {
                letterSpacing = style.letterSpacing.value / style.fontSize.value
            }
            style.fontFeatureSettings?.let { fontFeatureSettings = it }
        }
    }
}

private fun Paint.inkBounds(text: String): Rect = Rect().also { getTextBounds(text, 0, text.length, it) }

/** The space before the first letter of [text] and after its last, in pixels, from the font itself. */
@Composable
internal fun rememberSideBearings(text: String, style: TextStyle): Pair<Float, Float> {
    val paint = rememberInkPaint(style)
    return remember(text, paint) {
        if (paint == null || text.isEmpty()) return@remember 0f to 0f
        val ink = paint.inkBounds(text)
        ink.left.toFloat() to (paint.measureText(text) - ink.right)
    }
}

/** How wide [text] is from its first letter's ink to its last, on one line, as [inkSides] lays it out. */
@Composable
fun textInkWidth(text: String, style: TextStyle): Dp {
    if (text.isEmpty()) return 0.dp
    val (start, end) = rememberSideBearings(text, style)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val width = remember(text, style, density) { measurer.measure(text, style, softWrap = false).size.width }
    return with(density) { (width - start.roundToInt() - end.roundToInt()).toDp() }
}

/** Moves a start-aligned text left by its first letter's side bearing, so its ink sits on the keyline. */
@Composable
fun Modifier.inkStart(text: String, style: TextStyle): Modifier {
    val start = rememberSideBearings(text, style).first.roundToInt()
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.placeRelative(-start, 0) }
    }
}

/** A one-line label whose layout is its ink from its first letter to its last. */
@Composable
fun Modifier.inkSides(text: String, style: TextStyle): Modifier {
    val (start, end) = rememberSideBearings(text, style).let { it.first.roundToInt() to it.second.roundToInt() }
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.offset(start + end, 0))
        layout((placeable.width - start - end).coerceAtLeast(0), placeable.height) {
            placeable.placeRelative(-start, 0)
        }
    }
}

/**
 * Centres a centred one-line text by its ink: a box centred by its advance sits off by half the
 * difference of its side bearings ("B" has more on the left than "r" on the right).
 */
@Composable
fun Modifier.inkCentre(text: String, style: TextStyle): Modifier {
    val (start, end) = rememberSideBearings(text, style)
    val shift = ((end - start) / 2f).roundToInt()
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val oneLine = placeable[FirstBaseline] == placeable[LastBaseline]
        layout(placeable.width, placeable.height) { placeable.placeRelative(if (oneLine) shift else 0, 0) }
    }
}

/** The layout runs from the first line's cap top ([top]) to the last baseline ([bottom]). */
fun Modifier.textInk(style: TextStyle, top: Boolean = true, bottom: Boolean = true): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val first = placeable[FirstBaseline]
        val last = placeable[LastBaseline]
        if (first == AlignmentLine.Unspecified || last == AlignmentLine.Unspecified) {
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val cap = capHeight(style).roundToPx()
        val trim = if (top) first - cap else 0
        val end = if (bottom) last else placeable.height
        layout(placeable.width, end - trim) { placeable.place(0, -trim) }
    }

/**
 * A one-line text whose layout runs from its highest ink to its lowest: for a line with no
 * capitals ("or"), which is spaced from its own ink rather than from a cap top.
 */
@Composable
fun Modifier.inkHeight(text: String, style: TextStyle): Modifier {
    val paint = rememberInkPaint(style)
    val ink = remember(text, paint) { if (paint == null || text.isEmpty()) null else paint.inkBounds(text) }
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val baseline = placeable[FirstBaseline]
        if (ink == null || ink.isEmpty || baseline == AlignmentLine.Unspecified) {
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        layout(placeable.width, ink.height()) { placeable.place(0, -ink.top - baseline) }
    }
}
