package io.github.hazymorning.amsel.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hazymorning.amsel.ui.theme.PILL_ALPHA
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.controls
import io.github.hazymorning.amsel.ui.theme.rememberPress

/**
 * A small glyph-and-word button on a quiet pill: Play on's Group, Search's Clear. [height] high
 * (36 dp; Play on's as high as Now Playing's speaker pill), its glyph's ink and its word's ink
 * [PillPadding] from its ends and [PillGap] apart, in the controls' ink; its 48 dp target reaches
 * past the pill. The glyph is as large as the word ([wordGlyph]). A word longer than [maxText]
 * ends in an ellipsis. [strong] sets the word in bold. [description] is what TalkBack says for it.
 */
@Composable
fun QuietPill(
    @DrawableRes icon: Int,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = text,
    maxText: Dp = Dp.Unspecified,
    strong: Boolean = false,
    height: Dp = Sizes.PillHeight,
) {
    val colors = MaterialTheme.colorScheme
    val style = pillWords(strong)
    Row(
        modifier.quietGround(onClick, description, height).padding(horizontal = PillPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkGlyph(icon, tint = colors.controls, size = wordGlyph(style))
        Spacer(Modifier.width(PillGap))
        Text(
            text,
            style = style,
            color = colors.controls,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // From the capitals to the baseline, so the word is centred by its letters, not its line.
            modifier = Modifier.widthIn(max = maxText).clearAndSetSemantics {}.textInk(style).inkSides(text, style),
        )
    }
}

/**
 * A glyph alone on the pills' quiet ground, for what stands beside a pill and needs no word of
 * its own: Play on's Ungroup beside Group. As high as a pill and round, its glyph as large and as
 * heavy as a pill's; at a large font it widens, keeping its glyph [GlyphPadding] from its ends.
 * Its 48 dp target reaches past it on every side. [description] is what TalkBack says for it.
 */
@Composable
fun QuietGlyph(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = Sizes.PillHeight,
) {
    Box(
        modifier
            .quietGround(onClick, description, height, round = true)
            .widthIn(min = height)
            .padding(horizontal = GlyphPadding),
        contentAlignment = Alignment.Center,
    ) {
        InkGlyph(icon, tint = MaterialTheme.colorScheme.controls, size = wordGlyph(pillWords()))
    }
}

/** The pills' ground and the 48 dp target around it, which a [round] one needs at its sides too. */
@Composable
private fun Modifier.quietGround(
    onClick: () -> Unit,
    description: String,
    height: Dp,
    round: Boolean = false,
): Modifier {
    val reach = (Sizes.TouchTarget - height) / 2
    val sides = if (round) reach else 0.dp
    return overhang(start = sides, top = reach, end = sides, bottom = reach)
        .clickable(null, rememberPress(Shapes.Pill), role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = description }
        .padding(horizontal = sides, vertical = reach)
        .heightIn(min = height)
        .clip(Shapes.Pill)
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = PILL_ALPHA))
}

@Composable
private fun pillWords(strong: Boolean = false): TextStyle = MaterialTheme.typography.titleSmall.copy(
    fontSize = PillText,
    lineHeight = PillLine,
    letterSpacing = 0.sp,
    fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold,
)

private val PillPadding = 16.dp
private val PillGap = 8.dp
private val PillText = 16.sp
private val PillLine = 22.sp

/** A 16 dp glyph's distance to the top and foot of its 36 dp ground: the same to its sides. */
private val GlyphPadding = 10.dp
