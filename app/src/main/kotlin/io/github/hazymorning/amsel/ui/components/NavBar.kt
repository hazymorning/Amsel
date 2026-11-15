package io.github.hazymorning.amsel.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space

/** Over the places' glyphs, and under them at least, so they stand in the middle of their band. */
private val AroundPlaces = 20.dp

/** Under the glyphs where Android shows its buttons: they have their own room under that. */
private val UnderPlacesOverButtons = Space.S3

private val TabGlyph = Sizes.Icon

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
