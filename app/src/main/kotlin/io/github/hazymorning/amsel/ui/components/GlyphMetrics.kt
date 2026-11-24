package io.github.hazymorning.amsel.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.theme.DISABLED_ALPHA
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.rememberPress
import kotlin.math.abs
import kotlin.math.roundToInt

/** A glyph's ink on its 256-unit grid; [mirrored] glyphs flip in right-to-left layouts. */
@Immutable
internal data class GlyphBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val mirrored: Boolean,
)

private const val GRID = 256f

/** A glyph's other cuts beside its Regular one ([IconWeightsTable]); [heavy] only where it is drawn at 12 dp. */
@Immutable
internal class IconWeights(@DrawableRes val bold: Int, @DrawableRes val light: Int, @DrawableRes val heavy: Int? = null)

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
    val cuts = IconWeightsTable[icon] ?: return icon

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

internal fun glyphBoundsOf(@DrawableRes icon: Int): GlyphBounds =
    IconBoundsTable[icon] ?: GlyphBounds(0f, 0f, GRID, GRID, false)

fun glyphWidth(@DrawableRes icon: Int, size: Dp = Sizes.Icon): Dp =
    glyphBoundsOf(weighted(icon, size)).let { size * ((it.right - it.left) / GRID) }

fun glyphHeight(@DrawableRes icon: Int, size: Dp = Sizes.Icon): Dp =
    glyphBoundsOf(weighted(icon, size)).let { size * ((it.bottom - it.top) / GRID) }

/** How far below the top of its [size] box a glyph's ink starts. */
fun glyphTop(@DrawableRes icon: Int, size: Dp = Sizes.Icon): Dp =
    size * (glyphBoundsOf(weighted(icon, size)).top / GRID)

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
enum class GlyphEdge { Start, End, Centre }

/**
 * A glyph whose layout is its ink from side to side, and with [trimHeight] also from top to bottom.
 * Otherwise it keeps the [size] box's height with the ink centred in it, so glyphs in a row centre
 * alike and a glyph centred on text centres its ink there, wherever the drawing sits in its box.
 */
@Composable
fun AlignedGlyph(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = Sizes.Icon,
    contentDescription: String? = null,
    trimHeight: Boolean = false,
) {
    val drawn = weighted(icon, size)
    val ink = glyphBoundsOf(drawn)
    Icon(
        painterResource(drawn),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
            .layout { measurable, constraints ->
                val box = size.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(box, box))
                val shift = glyphShift(box, ink, trimHeight)
                val width = (box * (ink.right - ink.left) / GRID).roundToInt()
                val height = if (trimHeight) (box * (ink.bottom - ink.top) / GRID).roundToInt() else box
                layout(width, height) {
                    // A mirrored glyph flips with the layout; a plain one keeps its drawing.
                    if (ink.mirrored) placeable.placeRelative(-shift) else placeable.place(-shift)
                }
            }
            .size(size),
    )
}

/**
 * How far an [AlignedGlyph] of [box] pixels moves its drawing so its ink starts at the layout's start,
 * and with [trimHeight] at its top; otherwise the ink centres in the box's height.
 */
private fun glyphShift(box: Int, ink: GlyphBounds, trimHeight: Boolean) = IntOffset(
    (box * ink.left / GRID).roundToInt(),
    if (trimHeight) {
        (box * ink.top / GRID).roundToInt()
    } else {
        (box * ((ink.top + ink.bottom) / 2 - GRID / 2) / GRID).roundToInt()
    },
)

/**
 * Where the point ([x], [y]) of [icon]'s 256 grid lands in its [AlignedGlyph] of [size], in pixels from
 * the layout's top left: for a mark set on the glyph's own lines, as the speaker's dot on its
 * outline. Not for a glyph that mirrors.
 */
internal fun Density.glyphPoint(
    @DrawableRes icon: Int,
    size: Dp,
    x: Float,
    y: Float,
    trimHeight: Boolean = false,
): Offset {
    val box = size.roundToPx()
    val shift = glyphShift(box, glyphBoundsOf(weighted(icon, size)), trimHeight)
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
    edge: GlyphEdge = GlyphEdge.Centre,
    tint: Color = LocalContentColor.current,
    size: Dp = Sizes.Icon,
    enabled: Boolean = true,
) {
    val spare = endReach(icon, size)
    Box(
        modifier
            .overhang(
                start = if (edge == GlyphEdge.Start) spare else 0.dp,
                end = if (edge == GlyphEdge.End) spare else 0.dp,
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
        AlignedGlyph(icon, tint = tint, size = size, contentDescription = contentDescription)
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
        AlignedGlyph(R.drawable.ic_drag, tint = MaterialTheme.colorScheme.outline, contentDescription = null)
    }
}

fun endReach(@DrawableRes icon: Int, size: Dp = Sizes.Icon): Dp = (Sizes.TouchTarget - glyphWidth(icon, size)) / 2
