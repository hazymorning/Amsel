package io.github.hazymorning.amsel.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hazymorning.amsel.ui.theme.PILL_ALPHA
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.controls
import io.github.hazymorning.amsel.ui.theme.rememberPress

/**
 * A small glyph-and-word button on a quiet pill: Search's Clear. 36 dp high, its glyph's ink and
 * its word's ink [PillPadding] from its ends and [PillGap] apart, in the controls' ink; its 48 dp
 * target reaches past the pill. The glyph is as large as the word ([wordGlyph]).
 */
@Composable
fun QuietPill(@DrawableRes icon: Int, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val style = pillWords()
    Row(
        modifier.quietGround(onClick, text).padding(horizontal = PillPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlignedGlyph(icon, tint = colors.controls, size = wordGlyph(style))
        Spacer(Modifier.width(PillGap))
        Text(
            text,
            style = style,
            color = colors.controls,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // From the capitals to the baseline, so the word is centred by its letters, not its line.
            modifier = Modifier.clearAndSetSemantics {}.capToBaseline(style).trimSideBearings(text, style),
        )
    }
}

/** The pill's ground and the 48 dp target that reaches above and below it. */
@Composable
private fun Modifier.quietGround(onClick: () -> Unit, description: String): Modifier {
    val reach = (Sizes.TouchTarget - Sizes.PillHeight) / 2
    return overhang(top = reach, bottom = reach)
        .clickable(null, rememberPress(Shapes.Pill), role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = description }
        .padding(vertical = reach)
        .heightIn(min = Sizes.PillHeight)
        .clip(Shapes.Pill)
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = PILL_ALPHA))
}

@Composable
private fun pillWords(): TextStyle = MaterialTheme.typography.titleSmall.copy(
    fontSize = PillText,
    lineHeight = PillLine,
    letterSpacing = 0.sp,
    fontWeight = FontWeight.SemiBold,
)

private val PillPadding = 16.dp
private val PillGap = 8.dp
private val PillText = 16.sp
private val PillLine = 22.sp
