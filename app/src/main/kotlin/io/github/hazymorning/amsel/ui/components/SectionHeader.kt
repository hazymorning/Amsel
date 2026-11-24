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
 * [above]: how far the previous content's visible bottom sits inside its layout, so the cap top lands
 * [Space.SectionToHeader] below it; null puts the cap top at the layout's top. [below] runs from the
 * baseline to the next content's layout top.
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
                .trimStartBearing(title, style)
                .semantics { heading() },
        )
    }
}

/** [above] is measured to the cap top, [below] from the baseline. */
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
            .capToBaseline(style)
            .trimStartBearing(text, style)
            .semantics { heading() },
    )
}

@Composable
fun groupLabelStyle(): TextStyle = MaterialTheme.typography.labelLarge
