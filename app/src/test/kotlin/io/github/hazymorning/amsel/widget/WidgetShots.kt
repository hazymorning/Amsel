package io.github.hazymorning.amsel.widget

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.hazymorning.amsel.testing.ScreenshotTest
import io.github.hazymorning.amsel.testing.ShotVariant
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import io.github.hazymorning.amsel.ui.theme.isNight
import org.junit.Test

class WidgetShots : ScreenshotTest() {
    /** Four widgets on a home screen: music, a book with buttons of its own, nothing playing, signed out. */
    @Test fun widgets() = snap("widget", ShotVariant.entries) { HomeScreenOf() }

    @Test fun german() = snap("widget_german", qualifiers = "de") { HomeScreenOf() }

    @Test fun setup() = snap("widget_setup", ShotVariant.entries) { Setup() }

    @Test fun choices() = snapScreen("widget_choices", listOf(ShotVariant.Light, ShotVariant.Dark)) {
        Setup()
        AmselSheet(onDismiss = {}) {
            ButtonChoices(slot = 0, current = DefaultSlots[0], volumeLimit = 70, onChoose = {})
        }
    }

    @Composable
    private fun HomeScreenOf() {
        val night = MaterialTheme.colorScheme.isNight
        val wallpaper = if (night) NightWallpaper else DayWallpaper
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(wallpaper)).padding(top = 72.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                Widget(WidgetSample.music, DefaultSlots, "album_1")
                Widget(WidgetSample.book, WidgetSample.ownButtons, "audiobook_1")
            }
            Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                Widget(WidgetSample.idle, DefaultSlots, cover = null)
                Widget(WidgetSample.signedOut, DefaultSlots, cover = null)
            }
        }
    }

    @Composable
    private fun Widget(look: WidgetLook, slots: List<WidgetSlot>, cover: String?) {
        val context = LocalContext.current
        val density = LocalDensity.current.density
        val views = WidgetViews(context).build(
            widgetId = 1,
            look = look,
            slots = slots,
            cover = cover?.let { WidgetArt.cover(WidgetSample.cover(it)) },
            thumbs = WidgetSample.thumbs(slots, density),
        )
        Box(Modifier.size(WIDGET)) {
            AndroidView(factory = { views.apply(it, FrameLayout(it)) }, modifier = Modifier.fillMaxSize())
        }
    }

    @Composable
    private fun Setup() {
        val density = LocalDensity.current.density
        val state = WidgetSetupUi(
            slots = WidgetSample.ownButtons,
            look = WidgetSample.music,
            cover = WidgetArt.cover(WidgetSample.cover("album_1")),
            thumbs = WidgetSample.thumbs(WidgetSample.ownButtons, density),
            volumeLimit = 70,
        )
        WidgetSetupScreen(state, widgetId = 1, size = DpSize(WIDGET, WIDGET), onSlot = {}, onDone = {}, onBack = {})
    }

    private companion object {
        /** Two cells by two on a usual phone. */
        val WIDGET = 180.dp

        /** A home screen's own picture behind the widgets, as calm as most. */
        val DayWallpaper = listOf(Color(0xFFDDE4DA), Color(0xFFB7C0B2))
        val NightWallpaper = listOf(Color(0xFF283029), Color(0xFF141714))
    }
}
