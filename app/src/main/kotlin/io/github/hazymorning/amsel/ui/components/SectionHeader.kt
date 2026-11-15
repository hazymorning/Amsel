package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.capHeight
import io.github.hazymorning.amsel.ui.theme.lineHeight
import io.github.hazymorning.amsel.ui.theme.rememberPress
import io.github.hazymorning.amsel.ui.theme.spaced

/**
 * A section's heading on the margin; when the source has more, the whole row is one tap target
 * that leads to all of it. [above] is how far the previous content's
 * last ink sits inside its layout, so the heading's capitals stand [Space.SectionToHeader] under
 * it; null puts the capitals at the layout's top. [below] runs from the baseline to the
 * content's layout top. [onLongClick] is a shortcut with no press mark (Home's edit mode); TalkBack
 * offers it as [longClickLabel].
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
    above: Dp? = 0.dp,
    below: Dp = Space.HeadingToRow,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    val style = MaterialTheme.typography.titleLarge
    val cap = with(LocalDensity.current) { capHeight(style) }
    val seeAll = stringResource(R.string.see_all)
    val press = rememberPress(Shapes.Tile)
    val tap = if (onSeeAll != null) {
        Modifier.combinedClickable(
            interactionSource = null,
            indication = press,
            onClickLabel = seeAll,
            role = Role.Button,
            onLongClickLabel = longClickLabel,
            onLongClick = onLongClick,
            onClick = onSeeAll,
        )
    } else if (onLongClick != null) {
        Modifier
            .pointerInput(onLongClick) { detectTapGestures(onLongPress = { onLongClick() }) }
            .semantics(mergeDescendants = true) {
                onLongClick(longClickLabel) {
                    onLongClick()
                    true
                }
            }
    } else {
        Modifier
    }
    val top = if (above == null) cap else with(LocalDensity.current) { spaced(Space.SectionToHeader) } - above + cap
    val reach = with(LocalDensity.current) { ((Sizes.TouchTarget - lineHeight(style)) / 2).coerceAtLeast(0.dp) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Keyline.Margin - Space.BandText)
            .baselineFrame(top = top, bottom = below)
            .overhang(top = reach, bottom = reach)
            .then(tap)
            .padding(horizontal = Space.BandText, vertical = reach),
    ) {
        Words(
            title,
            style,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f, fill = false)
                .alignByBaseline()
                .inkStart(title, style)
                .semantics { heading() },
        )
    }
}

/**
 * A small heading over a group of rows, on the margin (or at [start], with the rows' words), smaller
 * and firmer than the rows so it never reads as one of them: [above] runs to its capitals, [below]
 * from its baseline.
 */
@Composable
fun GroupLabel(text: String, above: Dp, below: Dp, start: Dp = Keyline.Margin) {
    val style = groupLabelStyle()
    Text(
        text,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = start, end = Keyline.Margin, top = above, bottom = below)
            .textInk(style)
            .inkStart(text, style)
            .semantics { heading() },
    )
}

/** Group labels: the captions' style, a step above the rows' second lines. */
@Composable
fun groupLabelStyle(): TextStyle = MaterialTheme.typography.labelLarge
