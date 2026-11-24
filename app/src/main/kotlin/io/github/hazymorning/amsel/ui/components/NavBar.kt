package io.github.hazymorning.amsel.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.displayName
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space

private val AroundPlaces = 20.dp

/** The gap above Android's navigation buttons, which take the inset below it. */
private val UnderPlacesOverButtons = Space.S3

private val TabGlyph = Sizes.Icon

private val Dot = 8.dp

/** A ring in the band's colour around the dot, separating it from the glyph's stroke. */
private val DotRing = 2.dp

private val Outward = 2.dp
private const val DIAGONAL = 0.70710677f

/**
 * The 45-degree point of the speaker glyph's top arc (centre (128, 88), radius 64 on its 256 grid),
 * where the dot sits on the outline; at the corner of the glyph's box it would not touch the glyph.
 */
private const val ARC_X = 128f
private const val ARC_Y = 88f
private const val HALF_WAY = 64f * 0.70710677f

/**
 * Extends under the system bar. Behind Android's navigation buttons it paints the player's tone, so
 * the band does not read as one block with them.
 */
@Composable
fun NavBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val density = LocalDensity.current
    val buttons = if (WindowInsets.tappableElement.getBottom(density) > 0) {
        WindowInsets.navigationBars.getBottom(density)
    } else {
        0
    }
    val under = MaterialTheme.colorScheme.surfaceContainerLow
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (buttons > 0) {
                    Modifier.drawBehind {
                        drawRect(under, Offset(0f, size.height - buttons), Size(size.width, buttons.toFloat()))
                    }
                } else {
                    Modifier
                },
            )
            .selectableGroup(),
        verticalAlignment = Alignment.Top,
        content = content,
    )
}

@Composable
private fun Modifier.barItemPadding(): Modifier {
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val density = LocalDensity.current
    val buttons = WindowInsets.tappableElement.getBottom(density) > 0
    val inset = with(density) { bars.getBottom(this).toDp() }
    val below = if (buttons) inset + UnderPlacesOverButtons else maxOf(inset, AroundPlaces)
    return padding(top = AroundPlaces, bottom = below)
}

/** The padding lies inside the click area, so the whole strip down to the screen's edge takes the tap. */
@Composable
fun RowScope.NavBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    @DrawableRes icon: Int,
    @DrawableRes selectedIcon: Int,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val tint by animateColorAsState(
        if (selected) colors.onSurface else colors.onSurfaceVariant,
        Motion.effects(),
        label = "tab",
    )
    Box(
        Modifier
            .weight(1f)
            .semantics { this.selected = selected }
            .combinedClickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .then(Modifier.barItemPadding()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.height(TabGlyph), contentAlignment = Alignment.Center) {
            AlignedGlyph(
                if (selected) selectedIcon else icon,
                tint = tint,
                size = TabGlyph,
                contentDescription = label,
                trimHeight = true,
            )
        }
    }
}

/** A button, not a tab: it opens the speaker choice, not a page. */
@Composable
fun RowScope.NavBarSpeaker(
    speaker: SpeakerUi,
    playing: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(
        if (playing) R.string.speaker_button_playing else R.string.speaker_button_description,
        speaker.displayName(),
    )
    val dot by animateFloatAsState(if (playing) 1f else 0f, Motion.effects(), label = "speaker dot")
    val moss = colors.tertiary
    val band = colors.surfaceContainerHigh
    Box(
        Modifier
            .weight(1f)
            .combinedClickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics { contentDescription = description }
            .then(Modifier.barItemPadding()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.height(TabGlyph), contentAlignment = Alignment.Center) {
            AlignedGlyph(
                SpeakerButtonGlyph,
                Modifier.drawWithContent {
                    drawContent()
                    if (dot > 0f) {
                        // On the end side; the glyph is symmetric, so only the dot mirrors.
                        val ltr = layoutDirection == LayoutDirection.Ltr
                        val end = if (ltr) HALF_WAY else -HALF_WAY
                        val on =
                            glyphPoint(SpeakerButtonGlyph, TabGlyph, ARC_X + end, ARC_Y - HALF_WAY, trimHeight = true)
                        // Pushed out along the diagonal, so its ring does not cut the glyph's inner circle.
                        val out = Outward.toPx() * DIAGONAL
                        val at = on + Offset(if (ltr) out else -out, -out)
                        drawCircle(band, Dot.toPx() / 2 + DotRing.toPx(), at, alpha = dot)
                        drawCircle(moss, Dot.toPx() / 2, at, alpha = dot)
                    }
                },
                tint = colors.onSurfaceVariant,
                size = TabGlyph,
                trimHeight = true,
            )
        }
    }
}
