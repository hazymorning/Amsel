package io.github.hazymorning.amsel.ui.components

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import io.github.hazymorning.amsel.ui.theme.Radius
import io.github.hazymorning.amsel.ui.theme.capHeight
import kotlin.math.roundToInt

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
