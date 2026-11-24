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
 * [name] follows the title ("Volume · Kitchen") or, where one line cannot hold both, goes under it.
 * [value] keeps the width of [widestValue], so the title never moves or loses a letter as it changes.
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
 * Bottom padding that puts a last row's visible bottom [Space.SheetEdge] above the sheet's edge.
 * Content whose layout ends at its visible bottom (a button, a sentence) keeps [Space.SheetEdge] itself.
 */
@Composable
fun sheetEnd(lead: RowLead, twoLines: Boolean): Dp =
    (Space.SheetEdge - rowGlyphBottom(lead, twoLines)).coerceAtLeast(0.dp)

/** Keeps the width of [widest], so the text before it never moves while the value changes (a volume being dragged). */
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
