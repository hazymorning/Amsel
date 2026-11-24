package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.PlayerControl
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.navigation.DockControl

/**
 * [openDrag] is the drag that opens Now Playing, applied to the whole dock while something plays.
 * [covered]: Now Playing lies over the dock, so its song line stops moving.
 */
@Composable
fun Dock(
    nowPlaying: NowPlayingUi?,
    speaker: SpeakerUi,
    onOpen: () -> Unit,
    onControl: (PlayerControl) -> Unit,
    onSpeaker: () -> Unit,
    modifier: Modifier = Modifier,
    controls: List<DockControl> = DockControl.Default.visible,
    onDockControl: (DockControl) -> Unit = {},
    onArrange: () -> Unit = {},
    openDrag: Modifier = Modifier,
    covered: Boolean = false,
    tabs: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val sides = WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    Column(
        modifier
            .fillMaxWidth()
            // Hit-testable everywhere, so a touch between its controls never reaches the page below.
            .pointerInput(Unit) {}
            .then(if (nowPlaying != null) openDrag else Modifier),
    ) {
        PlayerCard(
            nowPlaying,
            speaker,
            covered,
            onOpen,
            onControl,
            onSpeaker,
            Modifier.background(colors.surfaceContainerLow).windowInsetsPadding(sides),
            controls = controls,
            onDockControl = onDockControl,
            onArrange = onArrange,
        )
        NavBar(Modifier.background(colors.surfaceContainerHigh).windowInsetsPadding(sides), content = tabs)
    }
}
