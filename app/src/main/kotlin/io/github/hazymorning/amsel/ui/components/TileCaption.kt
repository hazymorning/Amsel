package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.max
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.capHeight
import io.github.hazymorning.amsel.ui.theme.lineHeight
import io.github.hazymorning.amsel.ui.theme.secondLine
import io.github.hazymorning.amsel.ui.theme.spaced
import kotlin.math.ceil

/**
 * A title that wraps takes the second line's place, so titles that begin alike still read apart.
 * Every caption has the same height, so tiles align and none grows as it scrolls into view.
 */
@Composable
internal fun TileCaption(
    titleStyle: TextStyle,
    secondStyle: TextStyle,
    title: @Composable (maxLines: Int) -> Unit,
    second: (@Composable () -> Unit)? = null,
) {
    val (oneLine, room) = with(LocalDensity.current) {
        val top = spaced(Space.Caption) + capHeight(titleStyle)
        // A one-line title's height, and the caption's: high enough for a second title line or the second line.
        top.toPx() to ceil((top + max(lineHeight(titleStyle), secondLine(secondStyle))).toPx()).toInt() + 1
    }
    Layout(
        content = {
            Box { title(if (second != null) 2 else 1) }
            if (second != null) Box { second() }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val words = measurables[0].measure(loose)
        val wrapped = words.height > oneLine + 1
        val under = measurables.getOrNull(1)?.takeUnless { wrapped }?.measure(loose)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else maxOf(words.width, under?.width ?: 0)
        val height = if (measurables.size > 1) maxOf(room, words.height + (under?.height ?: 0)) else words.height
        layout(width, height) {
            words.place(0, 0)
            under?.place(0, words.height)
        }
    }
}
