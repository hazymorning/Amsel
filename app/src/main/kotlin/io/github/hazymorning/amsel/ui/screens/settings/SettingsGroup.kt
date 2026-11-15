package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.components.GroupLabel
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Radius
import io.github.hazymorning.amsel.ui.theme.SmoothShape
import io.github.hazymorning.amsel.ui.theme.Space

/** The rows of one [SettingsGroup], each given its place's shape. */
internal class SettingsRows {
    val rows = mutableListOf<@Composable (Shape) -> Unit>()

    fun row(content: @Composable (Shape) -> Unit) {
        rows += content
    }
}

/**
 * A block of settings, as Android's and iOS's own settings set theirs: its name over it, with the
 * rows' words, then its rows as one rounded block in the second surface, each row a part of it with
 * a hair of the page between them rather than a rule. [above] runs to the name's capitals.
 */
@Composable
internal fun SettingsGroup(label: String, above: Dp, content: SettingsRows.() -> Unit) {
    val rows = SettingsRows().apply(content).rows
    GroupLabel(label, above = above, below = LabelToBlock, start = RowWords)
    rows.forEachIndexed { index, row ->
        if (index > 0) Spacer(Modifier.height(BetweenRows))
        row(blockShape(first = index == 0, last = index == rows.lastIndex))
    }
}

/** A row's corners in its block: the block's own corners round fully, those between rows a little. */
private fun blockShape(first: Boolean, last: Boolean): Shape {
    val top = if (first) Radius.Large else BetweenCorner
    val bottom = if (last) Radius.Large else BetweenCorner
    return SmoothShape(top, top, bottom, bottom)
}

/** Where a block's sides stand from the screen's. */
internal val BlockEdge = Keyline.Margin - Space.BandInset

/** How far a row's words keep from its block's sides. */
internal val RowInset = Space.S4

/** Where a row's words start inside its block, and so its group's name over it. */
internal val RowWords = BlockEdge + RowInset

private val LabelToBlock = Space.LabelToContent
private val BetweenRows = 2.dp
private val BetweenCorner = 4.dp
