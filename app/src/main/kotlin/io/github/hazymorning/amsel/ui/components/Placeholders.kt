package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.capHeight
import io.github.hazymorning.amsel.ui.theme.secondLine
import io.github.hazymorning.amsel.ui.theme.spaced

/** Cycled by index, so a list of placeholders does not look uniform. */
private val TitleWidths = floatArrayOf(0.55f, 0.4f, 0.65f, 0.5f, 0.35f)
private val SubtitleWidths = floatArrayOf(0.3f, 0.25f, 0.35f)

/** A tile is narrow, so its lines take a larger share of it. */
private const val TILE_WIDER = 0.2f

private fun FloatArray.cycled(index: Int) = this[index % size]

@Composable
private fun blockTone(): Color = MaterialTheme.colorScheme.surfaceContainerLow

@Composable
fun SkeletonArt(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        Box(Modifier.fillMaxSize().clip(Shapes.cover(maxWidth)).background(blockTone()))
    }
}

/** [trimTop] and [trimBottom] trim the line as [capToBaseline] trims real text. */
@Composable
fun SkeletonText(
    style: TextStyle,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    fraction: Float = 1f,
    alignment: Alignment.Horizontal = Alignment.Start,
    trimTop: Boolean = false,
    trimBottom: Boolean = false,
) {
    val cap = with(LocalDensity.current) { capHeight(style) }
    val vertical = if (trimBottom && !trimTop) Alignment.Bottom else Alignment.CenterVertically
    Box(modifier.fillMaxWidth()) {
        Text(
            " ",
            style = style,
            color = Color.Transparent,
            maxLines = 1,
            modifier = Modifier
                .clearAndSetSemantics {}
                .then(
                    if (trimTop ||
                        trimBottom
                    ) {
                        Modifier.capToBaseline(style, top = trimTop, bottom = trimBottom)
                    } else {
                        Modifier
                    },
                ),
        )
        val sized = if (width != null) Modifier.width(width) else Modifier.fillMaxWidth(fraction)
        Box(
            sized
                .height(cap)
                .align(boxAlignment(alignment, vertical))
                .clip(Shapes.Pill)
                .background(blockTone()),
        )
    }
}

private fun boxAlignment(horizontal: Alignment.Horizontal, vertical: Alignment.Vertical): Alignment =
    when (horizontal) {
        Alignment.CenterHorizontally -> if (vertical == Alignment.Bottom) Alignment.BottomCenter else Alignment.Center
        Alignment.End -> if (vertical == Alignment.Bottom) Alignment.BottomEnd else Alignment.CenterEnd
        else -> if (vertical == Alignment.Bottom) Alignment.BottomStart else Alignment.CenterStart
    }

/** [index] picks the line widths, so neighbouring rows differ. */
@Composable
fun RowPlaceholder(index: Int, modifier: Modifier = Modifier, lead: RowLead = RowLead.Cover, lines: Int = 2) {
    val twoLines = lead == RowLead.Cover || lines > 1
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = Keyline.Margin, end = Keyline.Margin, bottom = rowGrowth(lead, twoLines))
            .heightIn(min = rowBand(lead, twoLines)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lead == RowLead.Cover) {
            SkeletonArt(Modifier.size(Sizes.RowArt))
            Spacer(Modifier.width(Space.Sibling))
        } else if (lead == RowLead.Glyph) {
            // The loaded rows will be numbered: leave their two-figure column empty.
            Spacer(Modifier.width(numberLeadWidth(0) + Space.Sibling))
        }
        RowLines(MaterialTheme.typography.bodyLarge, besideCover = lead == RowLead.Cover, Modifier.weight(1f)) {
            SkeletonText(MaterialTheme.typography.bodyLarge, fraction = TitleWidths.cycled(index))
            if (twoLines) SkeletonText(MaterialTheme.typography.bodyMedium, fraction = SubtitleWidths.cycled(index))
        }
    }
}

/** The caller sets the width; [index] picks the line widths. */
@Composable
fun TilePlaceholder(index: Int, modifier: Modifier = Modifier, twoLines: Boolean = true) {
    val type = MaterialTheme.typography
    Column(modifier) {
        SkeletonArt(Modifier.fillMaxWidth().aspectRatio(1f))
        TileCaption(
            type.titleSmall,
            type.bodyMedium,
            title = {
                SkeletonText(
                    type.titleSmall,
                    Modifier.underCover().padding(top = with(LocalDensity.current) { spaced(Space.Caption) }),
                    fraction = TitleWidths.cycled(index) + TILE_WIDER,
                    trimTop = true,
                    trimBottom = true,
                )
            },
            second = if (twoLines) {
                {
                    SkeletonText(
                        type.bodyMedium,
                        Modifier.underCover().paddingFromBaseline(
                            top = with(LocalDensity.current) {
                                secondLine(type.bodyMedium)
                            },
                        ),
                        fraction = SubtitleWidths.cycled(index) + TILE_WIDER,
                        trimBottom = true,
                    )
                }
            } else {
                null
            },
        )
    }
}

@Composable
fun CardPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(Sizes.RowArt)
            .clip(Shapes.Thumb)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

@Composable
fun SectionHeaderPlaceholder(modifier: Modifier = Modifier, above: Dp = 0.dp, below: Dp = Space.HeadingToRow) {
    val style = MaterialTheme.typography.titleLarge
    val cap = with(LocalDensity.current) { capHeight(style) }
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Keyline.Margin)
            .baselineFrame(top = Space.SectionToHeader - above + cap, bottom = below),
    ) {
        SkeletonText(style, width = Sizes.SkeletonLine, trimTop = true, trimBottom = true)
    }
}
