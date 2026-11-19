package io.github.hazymorning.amsel.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import io.github.hazymorning.amsel.R
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The widget measured as drawn: in the Plain look, on its flat ground, the ink's distance from each
 * edge, in dp. Every form keeps the same room on all four sides of its ink, as its fit says.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetInsetsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val music = WidgetSample.music.copy(art = null, artLarge = null)

    private data class Edges(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** [slots] showing [look] at [size], plain, drawn; the room around its ink. */
    private fun edges(slots: List<WidgetSlot>, look: WidgetLook, size: SizeF): Edges {
        val setup = WidgetSetup(style = WidgetStyle.Plain, slots = slots)
        val view = WidgetSample.plain(context, setup, look, size).apply(context, FrameLayout(context))
        val density = context.resources.displayMetrics.density
        val width = (size.width * density).roundToInt()
        val height = (size.height * density).roundToInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        val bitmap = createBitmap(width, height)
        view.draw(Canvas(bitmap))
        val ground = context.getColor(R.color.widget_background_plain)
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) < 255 || distance(pixel, ground) < INKED) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return Edges(left / density, top / density, (width - 1 - right) / density, (height - 1 - bottom) / density)
    }

    private fun distance(a: Int, b: Int) =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    private fun assertRoom(expected: Edges, measured: Edges, name: String) {
        println("$name: $measured")
        assertEquals("$name left", expected.left, measured.left, TOLERANCE)
        assertEquals("$name top", expected.top, measured.top, TOLERANCE)
        assertEquals("$name right", expected.right, measured.right, TOLERANCE)
        assertEquals("$name bottom", expected.bottom, measured.bottom, TOLERANCE)
    }

    private fun all(room: Float) = Edges(room, room, room, room)

    @Test fun aCardKeeps16AllRound() {
        assertRoom(all(16f), edges(DefaultSlots, music, WidgetSample.card), "card")
        assertRoom(all(16f), edges(WidgetSample.ownButtons, music, WidgetSample.card), "card, own buttons")
        assertRoom(all(16f), edges(DefaultSlots, music, SizeF(240f, 240f)), "card, three cells")
    }

    @Test fun aPlayerKeeps16AllRound() {
        assertRoom(all(16f), edges(DefaultSlots, music, WidgetSample.player), "player")
        assertRoom(all(16f), edges(WidgetSample.ownButtons, WidgetSample.idle, WidgetSample.player), "player, idle")
    }

    @Test fun aBarKeepsItsRoomAllRound() {
        assertRoom(all(16f), edges(DefaultSlots, music, WidgetSample.bar), "bar")
        assertRoom(all(12f), edges(DefaultSlots, music, SizeF(356f, 72f)), "bar, low")
    }

    /** A row's ends keep the room above and below its tallest ink. */
    @Test fun aRowKeepsItsRoomAllRound() {
        assertRoom(all(28f), edges(DefaultSlots, music, WidgetSample.row), "row")
        val glyphs = listOf(WidgetSlot(WidgetButton.Previous), WidgetSlot(WidgetButton.Next))
        assertRoom(all(39f), edges(glyphs, music, SizeF(172f, 96f)), "row of glyphs")
    }

    @Test fun aTilesButtonStandsInTheMiddle() {
        val measured = edges(listOf(WidgetSlot(WidgetButton.PlayPause)), music, WidgetSample.tile)
        assertRoom(Edges(18f, 26f, 18f, 26f), measured, "tile")
    }

    @Test fun largeTypeKeepsTheRoom() {
        RuntimeEnvironment.setFontScale(2f)
        assertRoom(all(16f), edges(DefaultSlots, music, WidgetSample.card), "card, 200 %")
        assertRoom(all(16f), edges(DefaultSlots, music, WidgetSample.player), "player, 200 %")
    }

    private companion object {
        /** A pixel this far from the ground, red, green and blue together, is ink. */
        const val INKED = 24

        /** Two pixels at xxhdpi: an edge's antialiasing. */
        const val TOLERANCE = 0.67f
    }
}
