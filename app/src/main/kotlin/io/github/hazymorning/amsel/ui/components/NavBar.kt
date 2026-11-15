package io.github.hazymorning.amsel.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.displayName
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space

/** Over the places' glyphs, and under them at least, so they stand in the middle of their band. */
private val AroundPlaces = 20.dp

/** Under the glyphs where Android shows its buttons: they have their own room under that. */
private val UnderPlacesOverButtons = Space.S3

private val TabGlyph = Sizes.Icon

/** The speaker's dot while music plays on it, on its glyph's outline. */
private val Dot = 6.dp

/** The ring of the band's own tone around the dot, which parts it from the line it sits on. */
private val DotRing = 1.5.dp

/**
 * Where the speaker glyph's outline turns at its top end, on the glyph's 256 grid: half way round
 * the arc of its top, whose centre is (128, 88) and radius 64. The dot's centre lies there, on the
 * line, so the outline runs into its ring from over the top and down the side and the dot is one
 * with the glyph. The corner of the glyph's box would leave it in the air past the arc.
 */
private const val ARC_X = 128f
private const val ARC_Y = 88f
private const val HALF_WAY = 64f * 0.70710677f

/**
 * The bar at the dock's foot, a band a tone apart from what plays above it: the app's places, each
 * its glyph alone. The band runs on under the system's own bar: its gesture handle lies in the room
 * under the glyphs, its buttons under that. The keyboard covers the dock, as it does Material's
 * navigation bar, leaving its room to what is typed.
 */
@Composable
fun NavBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.Top, content = content)
}

/** A place's room in the bar: over its glyph, and under it at least as much as the system's bar takes. */
@Composable
private fun Modifier.navPlace(): Modifier {
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val density = LocalDensity.current
    val buttons = WindowInsets.tappableElement.getBottom(density) > 0
    val inset = with(density) { bars.getBottom(this).toDp() }
    val below = if (buttons) inset + UnderPlacesOverButtons else maxOf(inset, AroundPlaces)
    return padding(top = AroundPlaces, bottom = below)
}

/**
 * Inactive: the glyph in the secondary tone. Active: ink, and filled. TalkBack reads the place's
 * name; the whole strip from the band's top to the screen's edge takes the tap.
 */
@Composable
fun RowScope.NavBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    @DrawableRes icon: Int,
    @DrawableRes selectedIcon: Int,
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
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = null,
                indication = null,
            )
            .then(Modifier.navPlace()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.height(TabGlyph), contentAlignment = Alignment.Center) {
            InkGlyph(
                if (selected) selectedIcon else icon,
                tint = tint,
                size = TabGlyph,
                contentDescription = label,
                inkHeight = true,
            )
        }
    }
}

/**
 * The speaker's place in the band, after Home: its glyph in the secondary tone, as a place's, which
 * opens the speaker choice rather than a page. While music plays on the speaker in use, a moss dot
 * sits on the glyph's outline where it turns at the top end, cut free of the line by a ring of the
 * band's tone. TalkBack hears "Speaker: Kitchen, playing. Choose speaker".
 */
@Composable
fun RowScope.NavBarSpeaker(speaker: SpeakerUi, playing: Boolean, onClick: () -> Unit) {
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
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .then(Modifier.navPlace()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.height(TabGlyph), contentAlignment = Alignment.Center) {
            InkGlyph(
                SpeakerButtonGlyph,
                Modifier.drawWithContent {
                    drawContent()
                    if (dot > 0f) {
                        // At the end side; the glyph is the same both ways round.
                        val end = if (layoutDirection == LayoutDirection.Ltr) HALF_WAY else -HALF_WAY
                        val at = inkPoint(SpeakerButtonGlyph, TabGlyph, ARC_X + end, ARC_Y - HALF_WAY, inkHeight = true)
                        drawCircle(band, Dot.toPx() / 2 + DotRing.toPx(), at, alpha = dot)
                        drawCircle(moss, Dot.toPx() / 2, at, alpha = dot)
                    }
                },
                tint = colors.onSurfaceVariant,
                size = TabGlyph,
                inkHeight = true,
            )
        }
    }
}
