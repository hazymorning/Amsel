package io.github.hazymorning.amsel.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.rememberPress

/**
 * A small glyph inside a ring, as a 48 dp button, the way a download shows on its way: the ring
 * fills with [progress], or turns while there is none yet; the glyph says what a tap does.
 */
@Composable
fun RingAction(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    progress: (() -> Float)?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Box(
        modifier
            .size(Sizes.TouchTarget)
            .clickable(
                interactionSource = null,
                indication = rememberPress(Shapes.Circle),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Spinner(size = Sizes.Icon, color = tint, progress = progress)
        InkGlyph(icon, tint = tint, size = RingGlyph)
    }
}

/** The glyph inside the ring: half its size, so the ring stays clear of it. */
private val RingGlyph = 12.dp
