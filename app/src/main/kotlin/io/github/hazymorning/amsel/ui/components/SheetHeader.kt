package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space

/**
 * A sheet's title as its first ink, on the margin like every other title: the layout runs from its
 * capitals to its last baseline. A title too long for a line takes a second before it is cut. A
 * sheet of one speaker names it after the title ("Volume · Kitchen"); where one line cannot hold
 * both, [name] stands under the title, on two lines if it must, and only a name longer than that
 * loses its end. [value] (a volume) stands at the end on the last baseline, in the room
 * [widestValue] takes: a title never loses a letter to a value, and nothing moves as it changes.
 */
@Composable
fun SheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    name: String? = null,
    value: String? = null,
    widestValue: String? = value,
) {
    val type = MaterialTheme.typography
    Row(modifier.fillMaxWidth().padding(horizontal = Keyline.Margin)) {
        Words(
            if (name == null) title else title + DOT_SEPARATOR + name,
            style = type.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (name == null) TITLE_LINES else TITLE_LINES + 1,
            shorter = name?.let { title + "\n" + it },
            modifier = Modifier
                .weight(1f)
                .alignBy(LastBaseline)
                .capToBaseline(type.titleLarge)
                .trimStartBearing(title, type.titleLarge)
                .semantics { heading() },
        )
        if (value != null) {
            Spacer(Modifier.width(Space.Sibling))
            EndValue(value, widestValue ?: value, Modifier.alignBy(LastBaseline))
        }
    }
}

private const val TITLE_LINES = 2

/**
 * What a sheet keeps under its last row of [lead] so the row's last ink (its last baseline, or its
 * cover's bottom) stands [Space.SheetEdge] over the sheet's bottom edge, as the title's capitals
 * stand under its top. Content that ends in its own ink (a button, a bar, a sentence) keeps
 * [Space.SheetEdge] itself.
 */
@Composable
fun sheetEnd(lead: RowLead, twoLines: Boolean): Dp =
    (Space.SheetEdge - rowGlyphBottom(lead, twoLines)).coerceAtLeast(0.dp)

/**
 * A value at a line's end, in the secondary tone, on the baseline of what it belongs to. It keeps
 * the room of its [widest] form and ends where that ends, so the words before it never move or
 * lose a letter while it changes (a volume under a finger).
 */
@Composable
internal fun EndValue(value: String, widest: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val room = remember(widest, style, density) {
        with(density) { measurer.measure(widest, style, softWrap = false, maxLines = 1).size.width.toDp() }
    }
    Text(
        value,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        textAlign = TextAlign.End,
        modifier = modifier.widthIn(min = room).capToBaseline(style),
    )
}
