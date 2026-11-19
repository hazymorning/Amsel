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
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import io.github.hazymorning.amsel.ui.theme.isNight
import org.junit.Test

class WidgetShots : ScreenshotTest() {
    /** Every form over its cover: two tiles and a row, two cards, a bar, a player. */
    @Test fun forms() = snap("widget", ShotVariant.entries) { HomeScreen { Forms() } }

    @Test fun german() = snap("widget_german", qualifiers = "de") { HomeScreen { Forms() } }

    /** Plain in the system's colours, and clear on the wallpaper, side by side. */
    @Test fun looks() = snap("widget_looks", listOf(ShotVariant.Light, ShotVariant.Dark)) { HomeScreen { Looks() } }

    /** Nothing playing, and signed out. */
    @Test fun states() = snap("widget_states") { HomeScreen { States() } }

    @Test fun setup() = snap("widget_setup", ShotVariant.entries) { Setup() }

    @Test fun choices() = snapScreen("widget_choices", listOf(ShotVariant.Light, ShotVariant.Dark)) {
        Setup()
        AmselSheet(onDismiss = {}) {
            ButtonChoices(slot = 0, current = DefaultSlots[0], volumeLimit = 70, onChoose = {})
        }
    }

    @Test fun speakers() = snapScreen("widget_speakers") {
        Setup()
        AmselSheet(onDismiss = {}) { SpeakerChoices(current = "kitchen", speakers = Rooms, onChoose = {}) }
    }

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
    private fun States() {
        Widget(WidgetSetup(), WidgetSample.idle, WidgetSample.card, column = 0, row = 0)
        Widget(WidgetSetup(), WidgetSample.signedOut, WidgetSample.card, column = 2, row = 0)
        Widget(WidgetSetup(), WidgetSample.idle, WidgetSample.player, column = 0, row = 2)
        Widget(WidgetSetup(), WidgetSample.signedOut, WidgetSample.bar, column = 0, row = 4)
        Widget(WidgetSetup(slots = emptyList()), WidgetSample.music, WidgetSample.tile, column = 0, row = 5)
        Widget(WidgetSetup(slots = emptyList()), WidgetSample.music, WidgetSample.row, column = 1, row = 5)
    }

    /** A home screen's picture behind the widgets, as calm as most, four cells across. */
    @Composable
    private fun HomeScreen(content: @Composable () -> Unit) {
        val wallpaper = if (MaterialTheme.colorScheme.isNight) NightWallpaper else DayWallpaper
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(wallpaper))) { content() }
    }

    /** [setup] showing [look] at [size], its top left in cell [column], [row]. */
    @Composable
    private fun Widget(setup: WidgetSetup, look: WidgetLook, size: SizeF, column: Int, row: Int) {
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
        val state = WidgetSetupUi(
            setup = setup,
            look = WidgetSample.music,
            picture = picture,
            size = WidgetSample.card,
            fits = 3,
            speakers = Rooms,
            volumeLimit = 70,
        )
        WidgetSetupScreen(
            state,
            wallpaper = Wallpaper,
            onStyle = {},
            onSpeaker = {},
            onSlot = {},
            onRemove = {},
            onDone = {},
            onBack = {},
        )
    }

    private companion object {
        /** Four cells 92 dp apart on a phone 411 dp wide; a widget is 12 dp less than its cells. */
        val Margin = 27.5.dp
        val Pitch = 92.dp
        val Top = 56.dp
        val RowPitch = 108.dp

        val DayWallpaper = listOf(Color(0xFFDDE4DA), Color(0xFFB7C0B2))
        val NightWallpaper = listOf(Color(0xFF283029), Color(0xFF141714))

        /** The wallpaper's main colour, as Android gives it to the widget's page. */
        val Wallpaper = Color(0xFFC9D2C5)

        val Rooms = listOf(WidgetSpeaker("kitchen", "Kitchen"), WidgetSpeaker("living", "Living room"))
    }
}
