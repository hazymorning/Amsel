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
 * Asks for Now Playing's cover while the music plays, at the size it takes on most phones (the
 * window's width less the margins), so the sheet rises with it in place rather than loading in.
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
