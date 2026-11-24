package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.capHeight
import io.github.hazymorning.amsel.ui.theme.lineHeight

/**
 * Laid out from cap top to baseline; the 48 dp target overhangs. The press shape is the line box plus
 * [PressRoom] above and below: room for accents and descenders, clear of the neighbouring lines.
 */
@Composable
fun TextLink(
    text: String,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    centred: Boolean = false,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val (band, reach) = with(LocalDensity.current) {
        val cap = capHeight(style)
        val band = (lineHeight(style) - cap) / 2 + PressRoom
        band to max((Sizes.TouchTarget - cap) / 2, band)
    }
    // A short name still gets a target 48 dp wide.
    val side = max(Space.S3, (Sizes.TouchTarget - textGlyphWidth(text, style)) / 2)
    val presses = remember { MutableInteractionSource() }
    Words(
        text,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (centred) TextAlign.Center else TextAlign.Start,
        modifier = modifier
            .overhang(start = side, top = reach, end = side, bottom = reach)
            .clickable(presses, indication = null, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .padding(vertical = reach - band)
            .clip(Shapes.Tile)
            .indication(presses, LocalIndication.current)
            .padding(horizontal = side, vertical = band)
            .capToBaseline(style)
            .then(if (centred) Modifier.centreOnGlyphs(text, style) else Modifier.trimStartBearing(text, style)),
    )
}

private val PressRoom = 2.dp
