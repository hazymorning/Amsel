package io.github.hazymorning.amsel.ui.screens.player

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.github.hazymorning.amsel.ui.components.capToBaseline
import io.github.hazymorning.amsel.ui.components.trimSideBearings
import io.github.hazymorning.amsel.ui.components.weighted
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.glyphFigures
import io.github.hazymorning.amsel.ui.theme.rememberPress

// Actions, not modes like the shuffle and repeat they replace, so always drawn in onSurface.
@Composable
internal fun SkipSeconds(
    @DrawableRes icon: Int,
    seconds: Int,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.Icon,
    onClick: () -> Unit,
) {
    val figure = seconds.toString()
    val style = with(LocalDensity.current) { glyphFigures(MaterialTheme.typography.labelMedium, size) }
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .size(Sizes.TouchTarget)
            .clickable(
                null,
                rememberPress(Shapes.Circle),
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(weighted(icon, size)),
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(size),
        )
        Text(
            figure,
            style = style,
            color = ink,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clearAndSetSemantics {}.capToBaseline(style).trimSideBearings(figure, style),
        )
    }
}
