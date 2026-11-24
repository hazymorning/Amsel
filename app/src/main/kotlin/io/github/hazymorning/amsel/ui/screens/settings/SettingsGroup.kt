package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.components.GroupLabel
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Radius
import io.github.hazymorning.amsel.ui.theme.SmoothShape
import io.github.hazymorning.amsel.ui.theme.Space

internal class SettingsRows {
    val rows = mutableListOf<@Composable (Shape) -> Unit>()

    fun row(content: @Composable (Shape) -> Unit) {
        rows += content
    }
}

/** [above] is measured to the name's capitals. */
@Composable
internal fun SettingsGroup(label: String, above: Dp, content: SettingsRows.() -> Unit) {
    val rows = SettingsRows().apply(content).rows
    GroupLabel(label, above = above, below = LabelToBlock, start = RowWords)
    rows.forEachIndexed { index, row ->
        if (index > 0) Spacer(Modifier.height(BetweenRows))
        row(blockShape(first = index == 0, last = index == rows.lastIndex))
    }
}

@Composable
internal fun SettingsBlock(content: SettingsRows.() -> Unit) {
    val rows = SettingsRows().apply(content).rows
    rows.forEachIndexed { index, row ->
        if (index > 0) Spacer(Modifier.height(BetweenRows))
        row(blockShape(first = index == 0, last = index == rows.lastIndex))
    }
}

@Composable
internal fun PageHint(text: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyMedium
    Words(
        text,
        style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        maxLines = Int.MAX_VALUE,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Keyline.Margin, end = Keyline.Margin, top = Space.S2, bottom = Space.Group),
    )
}

internal fun blockShape(first: Boolean, last: Boolean): Shape {
    val top = if (first) Radius.Large else BetweenCorner
    val bottom = if (last) Radius.Large else BetweenCorner
    return SmoothShape(top, top, bottom, bottom)
}

internal val BlockEdge = Keyline.Margin - Space.BandInset

internal val RowInset = Space.S4

internal val RowWords = BlockEdge + RowInset

internal val LabelToBlock = Space.LabelToContent
internal val BetweenRows = 2.dp
private val BetweenCorner = 4.dp
