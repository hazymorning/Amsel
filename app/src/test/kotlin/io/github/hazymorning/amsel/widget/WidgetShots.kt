package io.github.hazymorning.amsel.widget

import android.util.SizeF
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.hazymorning.amsel.testing.ScreenshotTest
import io.github.hazymorning.amsel.testing.ShotVariant
import io.github.hazymorning.amsel.ui.theme.isNight
import org.junit.Test

class WidgetShots : ScreenshotTest() {
    @Test fun forms() = snap("widget", ShotVariant.entries) { HomeScreen { Forms() } }

    @Test fun looks() = snap("widget_looks") { HomeScreen { Looks() } }

    @Test fun states() = snap("widget_states") { HomeScreen { States() } }

    @Test fun roomy() = snap("widget_roomy") { HomeScreen { Roomy() } }

    @Test fun parts() = snap("widget_parts") { HomeScreen { Parts() } }

    @Test fun darkened() = snap("widget_darkened") { HomeScreen { Darkened() } }

    @Test fun setup() = snap("widget_setup") { Setup() }

    @Composable
    private fun Forms() {
        val play = listOf(WidgetSlot(WidgetButton.PlayPause))
        val blueHour = listOf(WidgetSlot(WidgetButton.PlayItem, WidgetSample.blueHour))
        Widget(WidgetSetup(slots = play), WidgetSample.music, WidgetSample.tile, column = 0, row = 0)
        Widget(WidgetSetup(slots = blueHour), WidgetSample.music, WidgetSample.tile, column = 1, row = 0)
        Widget(WidgetSetup(), WidgetSample.music, WidgetSample.row, column = 2, row = 0)
        Widget(WidgetSetup(), WidgetSample.music, WidgetSample.card, column = 0, row = 1)
        Widget(WidgetSetup(slots = WidgetSample.ownButtons), WidgetSample.book, WidgetSample.card, column = 2, row = 1)
        Widget(WidgetSetup(), WidgetSample.music, WidgetSample.bar, column = 0, row = 3)
        Widget(
            WidgetSetup(slots = WidgetSample.ownButtons),
            WidgetSample.book,
            WidgetSample.player,
            column = 0,
            row = 4,
        )
    }

    @Composable
    private fun Darkened() {
        val darkened = WidgetSetup(style = WidgetStyle.Darkened)
        val play = listOf(WidgetSlot(WidgetButton.PlayPause))
        val blueHour = listOf(WidgetSlot(WidgetButton.PlayItem, WidgetSample.blueHour))
        val upNext = listOf(WidgetButton.UpNext, WidgetButton.PlayPause, WidgetButton.Next).map(::WidgetSlot)
        val rest = listOf(WidgetButton.Favourite, WidgetButton.UpNext, WidgetButton.PlayPause, WidgetButton.Next)
        val own = blueHour + rest.map(::WidgetSlot)
        Widget(darkened, WidgetSample.music, WidgetSample.row, column = 0, row = 0)
        Widget(darkened.copy(slots = blueHour), WidgetSample.music, WidgetSample.tile, column = 2, row = 0)
        Widget(darkened.copy(slots = play), WidgetSample.music, WidgetSample.tile, column = 3, row = 0)
        Widget(darkened, WidgetSample.music, WidgetSample.card, column = 0, row = 1)
        Widget(darkened.copy(slots = upNext), WidgetSample.book, WidgetSample.card, column = 2, row = 1)
        Widget(darkened, WidgetSample.music, WidgetSample.bar, column = 0, row = 3)
        Widget(darkened.copy(slots = own), WidgetSample.music, WidgetSample.player, column = 0, row = 4)
    }

    @Composable
    private fun Parts() {
        val after = WidgetSetup(words = WidgetWordsPosition.After)
        val pad = WidgetSetup(slots = DefaultSlots.take(4), showsWords = false)
        val small = SizeF(152f, 172f)
        Widget(after, WidgetSample.music, WidgetSample.card, column = 0, row = 0)
        Widget(pad, WidgetSample.music, WidgetSample.card, column = 2, row = 0)
        Widget(WidgetSetup(), WidgetSample.music, small, column = 0, row = 2)
        Widget(WidgetSetup(cells = WidgetCells.None), WidgetSample.music, small, column = 2, row = 2)
        Widget(WidgetSetup(), WidgetSample.music, WidgetSample.strip, column = 0, row = 4)
        Widget(after, WidgetSample.music, WidgetSample.bar, column = 0, row = 5)
        Widget(pad.copy(slots = DefaultSlots), WidgetSample.music, WidgetSample.bar, column = 0, row = 6)
    }

    @Composable
    private fun Looks() {
        val plain = WidgetSetup(style = WidgetStyle.Plain)
        val clear = WidgetSetup(style = WidgetStyle.Clear)
        val play = listOf(WidgetSlot(WidgetButton.PlayPause))
        Widget(plain, WidgetSample.music, WidgetSample.row, column = 0, row = 0)
        Widget(clear, WidgetSample.music, WidgetSample.row, column = 2, row = 0)
        Widget(plain, WidgetSample.music, WidgetSample.card, column = 0, row = 1)
        Widget(clear, WidgetSample.music, WidgetSample.card, column = 2, row = 1)
        Widget(plain, WidgetSample.music, WidgetSample.bar, column = 0, row = 3)
        Widget(clear, WidgetSample.music, WidgetSample.bar, column = 0, row = 4)
        Widget(plain.copy(slots = play), WidgetSample.music, WidgetSample.tile, column = 0, row = 5)
        Widget(clear.copy(slots = play), WidgetSample.music, WidgetSample.tile, column = 1, row = 5)
        Widget(
            plain.copy(slots = WidgetSample.ownButtons.take(1)),
            WidgetSample.music,
            WidgetSample.tile,
            column = 2,
            row = 5,
        )
        Widget(
            clear.copy(slots = WidgetSample.ownButtons.take(1)),
            WidgetSample.music,
            WidgetSample.tile,
            column = 3,
            row = 5,
        )
    }

    @Composable
    private fun Roomy() {
        val seven = DefaultSlots + listOf(WidgetSlot(WidgetButton.Repeat), WidgetSlot(WidgetButton.Quieter))
        Widget(WidgetSetup(slots = seven), WidgetSample.music, WidgetSample.player, column = 0, row = 0)
        Widget(WidgetSetup(), WidgetSample.music, WidgetSample.tallCard, column = 0, row = 2)
        Widget(WidgetSetup(), WidgetSample.book, WidgetSample.tallCard, column = 2, row = 2)
        Widget(WidgetSetup(slots = seven), WidgetSample.music, WidgetSample.bar, column = 0, row = 5)
    }

    @Composable
    private fun States() {
        Widget(WidgetSetup(), WidgetSample.idle, WidgetSample.card, column = 0, row = 0)
        Widget(WidgetSetup(), WidgetSample.signedOut, WidgetSample.card, column = 2, row = 0)
        Widget(WidgetSetup(), WidgetSample.idle, WidgetSample.player, column = 0, row = 2)
        Widget(WidgetSetup(), WidgetSample.signedOut, WidgetSample.bar, column = 0, row = 4)
        Widget(WidgetSetup(slots = emptyList()), WidgetSample.music, WidgetSample.tile, column = 0, row = 5)
        Widget(WidgetSetup(slots = emptyList()), WidgetSample.music, WidgetSample.row, column = 1, row = 5)
    }

    @Composable
    private fun HomeScreen(content: @Composable () -> Unit) {
        val wallpaper = if (MaterialTheme.colorScheme.isNight) NightWallpaper else DayWallpaper
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(wallpaper))) { content() }
    }

    @Composable
    private fun Widget(setup: WidgetSetup, look: WidgetContent, size: SizeF, column: Int, row: Int) {
        val context = LocalContext.current
        val night = MaterialTheme.colorScheme.isNight
        val views =
            remember(setup, look, size, night) { WidgetSample.drawn(context, setup, look, size, clearInk = !night) }
        Box(
            Modifier
                .offset(x = Margin + Pitch * column, y = Top + RowPitch * row)
                .size(size.width.dp, size.height.dp),
        ) {
            AndroidView(factory = { views.apply(it, FrameLayout(it)) }, modifier = Modifier.fillMaxSize())
        }
    }

    @Composable
    private fun Setup() {
        val context = LocalContext.current
        val setup = WidgetSetup(slots = WidgetSample.ownButtons)
        val picture = remember { WidgetSample.drawn(context, setup, WidgetSample.music, WidgetSample.card) }
        val state = WidgetSetupUiState(
            setup = setup,
            look = WidgetSample.music,
            picture = picture,
            size = WidgetSample.card,
            fits = 3,
            standing = setOf(1, 2, 3),
            speakers = Rooms,
            volumeLimit = 70,
        )
        WidgetSetupScreen(
            state,
            wallpaper = Wallpaper,
            onSheet = {},
            onRemove = {},
            onReorder = {},
            onShowWords = {},
            onMoveWords = {},
            onDone = {},
            onBack = {},
        )
    }

    private companion object {
        /** Four cells 92 dp apart on a 411 dp phone; a widget is 12 dp smaller than its cells. */
        val Margin = 27.5.dp
        val Pitch = 92.dp
        val Top = 56.dp
        val RowPitch = 108.dp

        val DayWallpaper = listOf(Color(0xFFDDE4DA), Color(0xFFB7C0B2))
        val NightWallpaper = listOf(Color(0xFF283029), Color(0xFF141714))

        val Wallpaper = Color(0xFFC9D2C5)

        val Rooms = listOf(WidgetSpeaker("kitchen", "Kitchen"), WidgetSpeaker("living", "Living room"))
    }
}
