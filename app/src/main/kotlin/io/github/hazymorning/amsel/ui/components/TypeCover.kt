package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.hazymorning.amsel.ui.model.MediaKind

/**
 * A cover set in type, where a playlist, album or book has no picture: its name in the title face
 * from the top corner, as a title page has it, or for an artist, and on any cover too small for a
 * name, its initials. Ink on the cover's own tone, so a missing picture looks set rather than lost.
 * Its letters keep their size at every font scale: it is a picture, and the row or tile names it.
 */
@Composable
internal fun TypeCover(name: String, kind: MediaKind, side: Dp, modifier: Modifier = Modifier) {
    val initials = kind == MediaKind.Artist || side < NameFrom
    val face = MaterialTheme.typography.displaySmall
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    if (initials) {
        val letters = initialsOf(name) ?: return
        val size = with(density) { (side * INITIALS_SHARE).toSp() }
        Box(modifier.fillMaxSize().clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Text(
                letters,
                style = face.copy(fontSize = size, lineHeight = size, letterSpacing = TIGHT.em),
                color = ink,
                maxLines = 1,
            )
        }
        return
    }
    val size = with(density) { (side * NAME_SHARE).toSp() }
    Box(modifier.fillMaxSize().clearAndSetSemantics {}.padding(side * INSET_SHARE)) {
        Text(
            name,
            style = face.copy(
                fontSize = size,
                lineHeight = size * NAME_LEADING,
                letterSpacing = TIGHT.em,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.FirstLineTop),
            ),
            color = ink,
            maxLines = NAME_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The first letter or figure of the first two words: "Road Trip 2026" is "RT", "Dunmore" is "D". */
internal fun initialsOf(name: String): String? = name
    .split(' ', '-', '_', '/', '&')
    .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
    .take(2)
    .joinToString("")
    .uppercase()
    .ifEmpty { null }

/** Below this a name would be too small to read, so the cover takes initials. */
private val NameFrom = 96.dp

private const val INITIALS_SHARE = 0.36f
private const val NAME_SHARE = 0.12f
private const val INSET_SHARE = 0.1f
private const val NAME_LEADING = 1.15f
private const val NAME_LINES = 4
private const val TIGHT = -0.02f
