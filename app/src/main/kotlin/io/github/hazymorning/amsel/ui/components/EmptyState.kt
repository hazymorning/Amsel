package io.github.hazymorning.amsel.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.groupAfter
import kotlin.math.roundToInt

private const val ANCHOR = 0.45f

/**
 * The glyph sits at the same height in the window on every screen, with or without a button; it
 * moves only when the space it is given cannot hold it there. In a list, where the height is
 * unbounded, the sentence starts a group's distance under what precedes it; there a null [icon]
 * leaves out a glyph the page already shows. [messageModifier] goes to the sentence, the one part
 * TalkBack always stops on (an empty list's refresh action).
 */
@Composable
fun EmptyState(
    @DrawableRes icon: Int?,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    messageModifier: Modifier = Modifier,
) {
    val glyph: (@Composable () -> Unit)? = icon?.let {
        { InkGlyph(it, tint = MaterialTheme.colorScheme.outline, size = Sizes.IconLarge, inkHeight = true) }
    }
    AnchoredMessage(glyph, message, modifier, actionLabel, onAction, messageModifier)
}

/** Waiting for something that has no skeleton (finding a server): a spinner in the glyph's place. */
@Composable
fun WaitingState(message: String, modifier: Modifier = Modifier) {
    AnchoredMessage(
        lead = { Spinner(size = Sizes.IconLarge, color = MaterialTheme.colorScheme.outline) },
        message = message,
        modifier = modifier,
    )
}

@Composable
private fun AnchoredMessage(
    lead: (@Composable () -> Unit)?,
    message: String,
    modifier: Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    messageModifier: Modifier = Modifier,
) {
    val body = MaterialTheme.typography.bodyLarge
    Layout(
        content = {
            lead?.invoke()
            Column(Modifier.padding(horizontal = Keyline.Margin), horizontalAlignment = Alignment.CenterHorizontally) {
                if (lead != null) Spacer(Modifier.height(Space.Sibling))
                Text(
                    message,
                    style = body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = messageModifier.widthIn(max = Sizes.Measure).textInk(body).inkCentre(message, body),
                )
                if (actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(with(LocalDensity.current) { groupAfter(body) }))
                    SecondaryButton(actionLabel, onAction)
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val glyph = if (lead != null) placeables.first() else null
        val text = placeables.last()
        val glyphHeight = glyph?.height ?: 0
        val groupHeight = glyphHeight + text.height
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else maxOf(glyph?.width ?: 0, text.width)
        val inList = !constraints.hasBoundedHeight
        val height = if (inList) Space.Group.roundToPx() + groupHeight else constraints.maxHeight
        layout(width, height) {
            val y = if (inList) {
                Space.Group.roundToPx()
            } else {
                val top = coordinates?.let { here ->
                    val window = here.findRootCoordinates().size.height
                    window * ANCHOR - here.positionInRoot().y - glyphHeight / 2f
                } ?: (height * ANCHOR - glyphHeight / 2f)
                top.roundToInt().coerceIn(0, (height - groupHeight).coerceAtLeast(0))
            }
            glyph?.place((width - glyph.width) / 2, y)
            text.place((width - text.width) / 2, y + glyphHeight)
        }
    }
}
