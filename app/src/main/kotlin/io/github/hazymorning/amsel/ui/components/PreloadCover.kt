package io.github.hazymorning.amsel.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import coil3.SingletonImageLoader
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.theme.Keyline

/**
 * Preloads Now Playing's cover at the size it has on most phones (window width less both margins),
 * so the sheet opens with the cover already in memory.
 */
@Composable
internal fun PreloadCover(art: ArtRef?) {
    val context = LocalContext.current
    val margins = with(LocalDensity.current) { Keyline.Margin.roundToPx() } * 2
    val side = (LocalWindowInfo.current.containerSize.width - margins).coerceAtLeast(1)
    LaunchedEffect(art, side) {
        if (art != null) SingletonImageLoader.get(context).enqueue(coverRequest(context, art, side))
    }
}
