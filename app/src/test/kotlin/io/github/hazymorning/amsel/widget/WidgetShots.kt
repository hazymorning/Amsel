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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.sample.SampleData
import io.github.hazymorning.amsel.testing.ScreenshotTest
import io.github.hazymorning.amsel.testing.ShotVariant
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.screens.library.LibraryKind
import io.github.hazymorning.amsel.ui.screens.library.LibraryUiState
import io.github.hazymorning.amsel.ui.screens.search.SearchScreen
import io.github.hazymorning.amsel.ui.screens.search.SearchUiState
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

    /** The cells as the person wants them: play and pause alone, as they come; every button; none. */
    @Test fun cells() = snap("widget_cells", listOf(ShotVariant.Light, ShotVariant.Dark)) { HomeScreen { Cells() } }

    /** Wide and tall: buttons as many as fit, the cover across a tall narrow one. */
    @Test fun roomy() = snap("widget_roomy", listOf(ShotVariant.Light, ShotVariant.Dark)) { HomeScreen { Roomy() } }

    /** Tall enough, the buttons take more rows: eight in two of four; five in three and two, before or after what plays. */
    @Test fun rows() = snap("widget_rows", listOf(ShotVariant.Light, ShotVariant.Dark)) { HomeScreen { Rows() } }

    /** Three rows high, four wide: the words a fifth larger beside the cover, every line with room. */
    @Test fun large() = snap("widget_large", listOf(ShotVariant.Light)) {
        HomeScreen {
            Widget(WidgetSetup(), WidgetSample.music, WidgetSample.tallPlayer, column = 0, row = 0)
            Widget(
                WidgetSetup(slots = WidgetSample.ownButtons),
                WidgetSample.book,
                WidgetSample.tallPlayer,
                column = 0,
                row = 3,
            )
        }
    }

    /** What plays after the buttons, or left out for a grid of them; a low strip; bare buttons where cells would not fit. */
    @Test fun parts() = snap("widget_parts", listOf(ShotVariant.Light, ShotVariant.Dark)) { HomeScreen { Parts() } }

    /** The darkened cover in every form: paper over the cover, round Play … buttons, Up next. */
    @Test fun darkened() = snap("widget_darkened", listOf(ShotVariant.Light, ShotVariant.Dark)) {
        HomeScreen { Darkened() }
    }

    /** Play …'s page with the field empty: the library by kind, played lately first. */
    @Test fun pick() = snap("widget_pick", listOf(ShotVariant.Light, ShotVariant.Dark)) {
        SearchScreen(
            state = SearchUiState(),
            onQueryChange = {},
            onPlayTrack = {},
            onOpen = {},
            onTrackMore = {},
            onMore = {},
            onRetry = {},
            bar = { PageTopBar(stringResource(R.string.widget_pick_title), onBack = {}) },
            browse = { insets ->
                val kinds = listOf(LibraryKind.Playlists, LibraryKind.Albums, LibraryKind.Artists, LibraryKind.Radio)
                val shelf = LibraryUiState(kind = LibraryKind.Playlists, items = SampleData.playlists, kinds = kinds)
                WidgetShelf(shelf, onKind = {}, onRetry = {}, onPick = {}, insets = insets)
            },
        )
    }

    @Test fun setup() = snap("widget_setup", ShotVariant.entries) { Setup() }

    @Test fun setupGerman() = snap("widget_setup_german", qualifiers = "de") { Setup() }

    @Test fun background() = snapScreen("widget_background") {
        Setup()
        AmselSheet(onDismiss = {}) { StyleChoices(current = WidgetStyle.Cover, onChoose = {}) }
    }

    @Test fun cellsGerman() = snapScreen("widget_cells_choices_german", qualifiers = "de") {
        Setup()
        AmselSheet(onDismiss = {}) { CellsChoices(current = WidgetCells.Play, onChoose = {}) }
    }

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
    private fun Rows() {
        val more = listOf(WidgetButton.Group, WidgetButton.UpNext, WidgetButton.Repeat)
        val eight = DefaultSlots + more.map(::WidgetSlot)
        Widget(WidgetSetup(slots = eight), WidgetSample.music, WidgetSample.tallPlayer, column = 0, row = 0)
        Widget(WidgetSetup(), WidgetSample.music, WidgetSample.tallCard, column = 0, row = 3)
        val after = WidgetSetup(words = WidgetWordsPlace.After)
        Widget(after, WidgetSample.music, WidgetSample.tallCard, column = 2, row = 3)
    }

    @Composable
    private fun Parts() {
        val after = WidgetSetup(words = WidgetWordsPlace.After)
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
    private fun Cells() {
        val all = WidgetSetup(cells = WidgetCells.All)
        val none = WidgetSetup(cells = WidgetCells.None)
        val play = listOf(WidgetSlot(WidgetButton.PlayPause))
        Widget(WidgetSetup(), WidgetSample.music, WidgetSample.row, column = 0, row = 0)
        Widget(all, WidgetSample.music, WidgetSample.row, column = 2, row = 0)
        Widget(none, WidgetSample.music, WidgetSample.row, column = 0, row = 1)
        Widget(all.copy(slots = play), WidgetSample.music, WidgetSample.tile, column = 2, row = 1)
        Widget(none.copy(slots = play), WidgetSample.music, WidgetSample.tile, column = 3, row = 1)
        Widget(all, WidgetSample.music, WidgetSample.card, column = 0, row = 2)
        Widget(none, WidgetSample.music, WidgetSample.card, column = 2, row = 2)
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
