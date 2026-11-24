package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.hazymorning.amsel.ui.theme.HAIRLINE_ALPHA

/**
 * Sizes follow the cover, not the font scale: it is a picture, and the row or tile around it carries
 * the name for TalkBack.
 */
@Composable
internal fun TypeCover(name: String, side: Dp, modifier: Modifier = Modifier) {
    val letter = monogramOf(name) ?: return
    val face = MaterialTheme.typography.displaySmall
    Box(modifier.fillMaxSize().clearAndSetSemantics {}) {
        Monogram(letter, face)
        if (side >= NameFrom) {
            val size = with(LocalDensity.current) { (side * NAME_SHARE).toSp() }
            Text(
                name,
                modifier = Modifier.padding(side * INSET_SHARE),
                style = face.copy(
                    fontSize = size,
                    lineHeight = size * NAME_LEADING,
                    letterSpacing = TIGHT.em,
                    lineHeightStyle = LineHeightStyle(
                        LineHeightStyle.Alignment.Center,
                        LineHeightStyle.Trim.FirstLineTop,
                    ),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = NAME_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Monogram(letter: String, face: TextStyle) {
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = HAIRLINE_ALPHA)
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val side = size.width
            val em = (side * MONOGRAM_SHARE).toDp().toSp()
            val layout = measurer.measure(
                letter,
                face.copy(fontSize = em, lineHeight = em, letterSpacing = 0.sp),
                density = this,
                layoutDirection = layoutDirection,
            )
            val start = side * MONOGRAM_START
            val x = if (layoutDirection == LayoutDirection.Rtl) side - start - layout.size.width else start
            val y = side * MONOGRAM_BASELINE - layout.firstBaseline
            onDrawBehind { drawText(layout, color = ink, topLeft = Offset(x, y)) }
        },
    )
}

internal fun monogramOf(name: String): String? = name.firstOrNull { it.isLetterOrDigit() }?.uppercase()?.take(1)

/** Below this the name would be too small to read, so only the letter shows. */
private val NameFrom = 96.dp

private const val MONOGRAM_SHARE = 1f
private const val MONOGRAM_START = 0.36f
private const val MONOGRAM_BASELINE = 1.10f
private const val NAME_SHARE = 0.12f
private const val INSET_SHARE = 0.1f
private const val NAME_LEADING = 1.15f
private const val NAME_LINES = 4
private const val TIGHT = -0.02f
