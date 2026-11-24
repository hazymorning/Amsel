package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.lineHeight

/**
 * A tip: one sentence about what the app keeps out of sight, after a tip's mark, on a card of the
 * first surface between the margins. The mark is its own and no control's: the glyph of what the
 * tip tells of would look like the button for it. The card goes with a swipe to either side or
 * by its close glyph, [onGone], and is not said again. A longer sentence wraps and the card grows.
 */
@Composable
fun TipCard(text: String, onGone: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyLarge
    val line = with(LocalDensity.current) { lineHeight(style) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Keyline.Margin)
            .swipeAway(onGone)
            .semantics(mergeDescendants = true) {
                dismiss {
                    onGone()
                    true
                }
            }
            .clip(Shapes.Tile)
            .background(colors.surfaceContainerLow)
            .padding(start = Space.Sibling),
    ) {
        Box(Modifier.padding(top = Inside).height(line), contentAlignment = Alignment.Center) {
            AlignedGlyph(R.drawable.ic_tip, tint = colors.onSurfaceVariant, size = wordGlyph(style))
        }
        Spacer(Modifier.width(GlyphToWords))
        Text(
            text,
            style = style,
            color = colors.onSurface,
            modifier = Modifier.weight(1f).padding(vertical = Inside),
        )
        // The close glyph's target reaches the card's corner; its ink stands as far in as the words do below.
        IconAction(
            R.drawable.ic_close,
            stringResource(R.string.tip_dismiss),
            onGone,
            Modifier.padding(top = Inside - (Sizes.TouchTarget - line) / 2),
            tint = colors.onSurfaceVariant,
            size = Sizes.IconSmall,
        )
    }
}

private val Inside = Space.Sibling
private val GlyphToWords = Space.S3
