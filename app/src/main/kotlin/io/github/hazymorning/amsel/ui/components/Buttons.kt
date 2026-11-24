package io.github.hazymorning.amsel.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.theme.DISABLED_ALPHA
import io.github.hazymorning.amsel.ui.theme.Elevation
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.elevationShadow
import io.github.hazymorning.amsel.ui.theme.firm
import io.github.hazymorning.amsel.ui.theme.lineHeight
import io.github.hazymorning.amsel.ui.theme.rememberPress

/** A label's ends stand this far in from a button's. */
private val ButtonPadding = Space.Group

/**
 * The body of every labelled button: a pill of [fill] with its label in [content], 48 dp tall. A
 * label is never cut: it wraps and the button grows. Not yet usable, any button is the quiet second
 * tone with a faint label, so it never outweighs what has to be done first.
 */
@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    fill: Color,
    content: Color,
    modifier: Modifier,
    enabled: Boolean,
    custom: (@Composable RowScope.() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val label = if (enabled) content else colors.outline
    Row(
        modifier
            .heightIn(min = Sizes.ButtonHeight)
            .clickable(null, rememberPress(shape = null), enabled = enabled, role = Role.Button, onClick = onClick)
            .clip(Shapes.Pill)
            .background(if (enabled) fill else colors.surfaceContainerHigh)
            .padding(horizontal = ButtonPadding, vertical = Space.S2),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides label) {
            if (custom != null) {
                custom()
            } else {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge.firm(),
                    color = label,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The one primary action of a screen: ink, with its label in the page colour. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: (@Composable RowScope.() -> Unit)? = null,
) = PillButton(
    text,
    onClick,
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.onPrimary,
    modifier,
    enabled,
    content,
)

/**
 * A second action beside or under the primary one, which should not compete: the second surface
 * tone, the label in ink.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: (@Composable RowScope.() -> Unit)? = null,
) = PillButton(
    text,
    onClick,
    MaterialTheme.colorScheme.surfaceContainerHigh,
    MaterialTheme.colorScheme.onSurface,
    modifier,
    enabled,
    content,
)

/**
 * A quiet action that is a glyph and a word: its layout is its ink from glyph to last letter, one
 * line tall; the 48 dp target reaches beyond. The glyph is as large as the word ([wordGlyph])
 * unless [iconSize] says otherwise.
 */
@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null,
    style: TextStyle = MaterialTheme.typography.bodyLarge.firm(),
    iconSize: Dp = Dp.Unspecified,
    gap: Dp = Space.Part,
) {
    val glyph = if (iconSize.isSpecified) iconSize else wordGlyph(style)
    val line = with(LocalDensity.current) { lineHeight(style) }
    val reachY = ((Sizes.TouchTarget - line) / 2).coerceAtLeast(0.dp)
    val reachX = Space.S3
    val described = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Row(
        modifier
            .overhang(start = reachX, top = reachY, end = reachX, bottom = reachY)
            .clickable(null, rememberPress(Shapes.Pill), role = Role.Button, onClick = onClick)
            .then(described)
            .padding(horizontal = reachX, vertical = reachY),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AlignedGlyph(icon, tint = color, size = glyph)
            Spacer(Modifier.width(gap))
        }
        Words(
            text,
            style = style,
            color = color,
            modifier = Modifier.trimSideBearings(text, style),
        )
    }
}

/**
 * A two-state switch drawn as a glyph: in the secondary tone when off ([offTint]), in ink when on.
 * A glyph that looks the same both ways (shuffle) shows a [dot] under it when on; one with a filled
 * glyph for on (the heart) needs none. Not [enabled], it shows its state dimmed and takes no tap.
 */
@Composable
fun ToggleIconButton(
    @DrawableRes icon: Int,
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dot: Boolean = false,
    enabled: Boolean = true,
    offTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = Sizes.Icon,
) {
    val colors = MaterialTheme.colorScheme
    val tint by animateColorAsState(if (on) colors.onSurface else offTint, Motion.effects(), label = "toggle")
    val shown by animateFloatAsState(if (on && dot) 1f else 0f, Motion.effects(), label = "dot")
    val below = glyphHeight(icon, size) / 2 + Sizes.ToggleDotGap + Sizes.ToggleDot / 2
    Box(
        modifier
            .size(Sizes.TouchTarget)
            .semantics { contentDescription = label }
            .toggleable(on, null, rememberPress(Shapes.Circle), enabled, Role.Switch, onValueChange = { onClick() })
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .drawWithContent {
                drawContent()
                if (shown > 0f) {
                    drawCircle(
                        colors.onSurface.copy(alpha = shown),
                        Sizes.ToggleDot.toPx() / 2,
                        center.copy(y = center.y + below.toPx()),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AlignedGlyph(icon, tint = tint, size = size)
    }
}

/**
 * A round play or pause in ink, the focal action: Now Playing's 72 dp and a page's 56; [tonal], a
 * disc in the second tone with the glyph in ink, for a surface that is no page (the dock). The
 * triangle sits a little right of the centre of its box, so it looks centred in the circle. A light
 * tick answers.
 */
@Composable
fun PlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.PagePlayButton,
    label: String? = null,
    tonal: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val background = if (tonal) colors.surfaceContainerHigh else colors.primary
    val glyphTint = if (tonal) colors.onSurface else colors.onPrimary
    val glyph = if (size >= Sizes.PlayButton) Sizes.IconLarge else Sizes.Icon
    val icon = if (playing) R.drawable.ic_pause_slim else R.drawable.ic_play
    val description = label ?: stringResource(if (playing) R.string.pause else R.string.play)
    val haptics = LocalHapticFeedback.current
    val target = maxOf(size, Sizes.TouchTarget)
    Box(
        modifier
            .overhang(
                start = (target - size) / 2,
                top = (target - size) / 2,
                end = (target - size) / 2,
                bottom = (target - size) / 2,
            )
            .size(target)
            .clickable(
                null,
                rememberPress(shape = null),
                role = Role.Button,
                onClickLabel = description,
                onClick = {
                    haptics.performHapticFeedback(
                        if (playing) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                    )
                    onClick()
                },
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .then(if (tonal) Modifier else Modifier.elevationShadow(Shapes.Circle, Elevation.Button))
                .clip(Shapes.Circle)
                .background(background),
            contentAlignment = Alignment.Center,
        ) {
            val shift = if (playing) 0.dp else glyphWidth(icon, glyph) / PLAY_SHIFT
            AlignedGlyph(icon, Modifier.offset(x = shift), tint = glyphTint, size = glyph)
        }
    }
}

/** The play triangle moves right by this share of its width to look centred. */
private const val PLAY_SHIFT = 8
