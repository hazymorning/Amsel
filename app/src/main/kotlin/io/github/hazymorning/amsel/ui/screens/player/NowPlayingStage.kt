package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.spaced
import kotlin.math.min

/**
 * The artwork [BarToArt] under the bar, then what plays, the line of where it is and the controls,
 * each a fixed step under the last; the utilities at the foot of the screen, and what room is left
 * between them and the controls. On a short screen the artwork gives way first. The words, the line
 * and the controls take the width inside [NowPlayingInset].
 */
@Composable
internal fun Stage(foot: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val margin = Keyline.Margin.roundToPx()
        val inner = (Keyline.Margin + NowPlayingInset).roundToPx()
        val loose = Constraints(maxWidth = width)
        val top = spaced(BarToArt).roundToPx()
        val minTop = spaced(Space.S4).roundToPx()
        val minBelow = spaced(Space.S8).roundToPx()
        val bottom = (foot - (Sizes.TouchTarget - Sizes.SpeakerPill) / 2).roundToPx()
        val toInfo = spaced(ArtToTitle).roundToPx()
        val toLine = spaced(ArtistToLine).roundToPx()
        val toControls = spaced(LineToControls).roundToPx()
        val info = measurables[1].measure(loose)
        val seek = measurables[2].measure(Constraints.fixedWidth(width - 2 * inner))
        val transport = measurables[3].measure(loose)
        val footer = measurables[4].measure(loose)
        val words = toInfo + info.height + toLine + seek.height + toControls + transport.height
        val room = height - words - minBelow - footer.height - bottom
        val side = min(width - margin * 2, room - minTop).coerceAtLeast(0)
        val art = measurables[0].measure(Constraints.fixed(side, side))
        val above = min(top, room - side).coerceAtLeast(minTop)
        layout(width, height) {
            var y = above
            art.place((width - side) / 2, y)
            y += side + toInfo
            info.place(0, y)
            y += info.height + toLine
            seek.place(inner, y)
            y += seek.height + toControls
            transport.place(0, y)
            footer.place(0, height - bottom - footer.height)
        }
    }
}

/**
 * The bar to the artwork; the artwork to the title's capitals; the artist's baseline to the line;
 * the line to the controls. The footer's grounds end [FooterRoom] above the screen's foot
 * ([footRoom]), the margin their sides keep; its row, a touch target tall, reaches a little
 * further down.
 */
private val BarToArt = 28.dp
private val ArtToTitle = 46.5.dp
private val ArtistToLine = 29.5.dp
private val LineToControls = 32.dp
private val FooterRoom = Keyline.Margin
private val OverButtons = Space.S3

/**
 * From the footer's grounds to the screen's foot: exactly as far as they stand from its sides, the
 * gesture handle lying in that room (a taller handle's room, where a phone has one); over
 * Android's buttons, their height and [OverButtons].
 */
@Composable
internal fun footRoom(): Dp {
    val density = LocalDensity.current
    val inset = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }
    val buttons = WindowInsets.tappableElement.getBottom(density) > 0
    return if (buttons) inset + OverButtons else maxOf(inset, FooterRoom)
}
