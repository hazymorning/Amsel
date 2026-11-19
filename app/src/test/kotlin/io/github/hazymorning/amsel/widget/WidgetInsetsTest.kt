package io.github.hazymorning.amsel.widget

import android.content.Context
import android.graphics.Bitmap
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The widget measured as drawn, in the Plain look on its flat ground: the room between its edge and
 * everything on it, and the gaps between its button cells, every cell shaded so each shows. Each form
 * is laid out for one size and drawn at others too, as a launcher may give a widget a little more or
 * less than it said: the frame and the gaps hold at all of them, and bare buttons stand as evenly.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetInsetsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val density get() = context.resources.displayMetrics.density
    private val music = WidgetSample.music.copy(art = null, artLarge = null)
    private val own = WidgetSample.ownButtons.take(3)

    private class Drawn(val bitmap: Bitmap, val ground: Int, val density: Float) {
        val width = bitmap.width
        val height = bitmap.height

        fun inked(x: Int, y: Int): Boolean {
            val pixel = bitmap.getPixel(x, y)
            return Color.alpha(pixel) == 255 && distance(pixel, ground) >= INKED
        }

        /** The room between the widget's edge and its ink, in dp: start, top, end, bottom. */
        fun frame(): List<Float> {
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (!inked(x, y)) continue
                    left = minOf(left, x)
                    right = maxOf(right, x)
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                }
            }
            return listOf(left, top, width - 1 - right, height - 1 - bottom).map { it / density }
        }

        /** The cells crossed by the line [y] dp down: each one's start and end, in dp. */
        fun cells(y: Float): List<Pair<Float, Float>> {
            val row = (y * density).roundToInt()
            val runs = mutableListOf<Pair<Float, Float>>()
            var start = -1
            for (x in 0..width) {
                val inside = x < width && inked(x, row)
                if (inside && start < 0) start = x
                if (!inside && start >= 0) {
                    runs += start / density to x / density
                    start = -1
                }
            }
            return runs
        }
    }

    /** [slots] showing [look], laid out for [planned] and drawn at [actual]. */
    private fun draw(
        slots: List<WidgetSlot>,
        look: WidgetLook,
        planned: SizeF,
        actual: SizeF = planned,
        cells: WidgetCells = WidgetCells.All,
        words: WidgetWordsPlace = WidgetWordsPlace.Before,
        showsWords: Boolean = true,
    ): Drawn {
        val setup = WidgetSetup(WidgetStyle.Plain, slots = slots, cells = cells, words = words, showsWords = showsWords)
        val view = laidOut(setup, look, planned, actual)
        val bitmap = createBitmap(view.width, view.height)
        view.draw(Canvas(bitmap))
        return Drawn(bitmap, context.getColor(R.color.widget_background_plain), density)
    }

    private fun laidOut(setup: WidgetSetup, look: WidgetLook, planned: SizeF, actual: SizeF = planned): View {
        val view = WidgetSample.plain(context, setup, look, planned).apply(context, FrameLayout(context))
        val width = (actual.width * density).roundToInt()
        val height = (actual.height * density).roundToInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        return view
    }

    private fun assertFrame(expected: Float, drawn: Drawn, name: String) {
        val (start, top, end, bottom) = drawn.frame()
        println("$name: start $start top $top end $end bottom $bottom")
        assertEquals("$name start", expected, start, TOLERANCE)
        assertEquals("$name top", expected, top, TOLERANCE)
        assertEquals("$name end", expected, end, TOLERANCE)
        assertEquals("$name bottom", expected, bottom, TOLERANCE)
    }

    /** The cells along the line [y] dp down: [count] of them, of one width, [WidgetSizes.GAP] apart. */
    private fun assertCells(drawn: Drawn, y: Float, count: Int, name: String) {
        val cells = drawn.cells(y)
        println("$name cells: $cells")
        assertEquals("$name cells", count, cells.size)
        val widths = cells.map { it.second - it.first }
        assertTrue("$name widths $widths", widths.max() - widths.min() <= TOLERANCE)
        cells.zipWithNext { a, b -> assertEquals("$name gap", WidgetSizes.GAP, b.first - a.second, TOLERANCE) }
    }

    /** Ink runs a glyph's parts leave on a line (a skip's bar and triangle), joined into one per glyph. */
    private fun glyphs(runs: List<Pair<Float, Float>>): List<Pair<Float, Float>> =
        runs.fold(mutableListOf()) { joined, run ->
            val last = joined.lastOrNull()
            if (last != null && run.first - last.second < GLYPH_PARTS_APART) {
                joined[joined.lastIndex] = last.first to run.second
            } else {
                joined += run
            }
            joined
        }

    /** Laid out for each size the launcher may say, drawn at it and at a little more and less. */
    private fun around(size: SizeF): List<SizeF> = listOf(
        size,
        SizeF(size.width - 8f, size.height - 8f),
        SizeF(size.width + 16f, size.height + 12f),
        SizeF(size.width - 16f, size.height),
    )

    @Test fun aCardKeepsItsFrameAndGaps() {
        for (actual in around(WidgetSample.card)) {
            val name = "card at $actual"
            val drawn = draw(DefaultSlots, music, WidgetSample.card, actual)
            assertFrame(WidgetSizes.PADDING, drawn, name)
            assertCells(drawn, actual.height - WidgetSizes.PADDING - WidgetSizes.CELL_HEIGHT / 2, 3, name)
        }
    }

    /** One row's cells are as high as under words, in its middle; the frame holds at the sides. */
    @Test fun aRowKeepsItsFrameAndGaps() {
        for (actual in around(WidgetSample.row)) {
            val name = "row at $actual"
            val drawn = draw(own, music, WidgetSample.row, actual)
            val (start, top, end, bottom) = drawn.frame()
            assertEquals("$name start", WidgetSizes.PADDING, start, TOLERANCE)
            assertEquals("$name end", WidgetSizes.PADDING, end, TOLERANCE)
            assertEquals("$name top and bottom", top, bottom, TOLERANCE)
            assertTrue("$name: cells of $top", top >= WidgetSizes.PADDING - TOLERANCE)
            assertCells(drawn, actual.height / 2, 3, name)
        }
    }

    /** Bare, the buttons reach into the frame, their glyphs each in the middle of its own target. */
    @Test fun bareButtonsStandEvenly() {
        for (actual in around(WidgetSample.row)) {
            val drawn = draw(DefaultSlots.take(3), music, WidgetSample.row, actual, cells = WidgetCells.None)
            val middles = glyphs(drawn.cells(actual.height / 2)).map { (it.first + it.second) / 2 }
            println("bare row at $actual: $middles")
            assertEquals("bare row at $actual", 3, middles.size)
            val pitch = (actual.width - 2 * WidgetSizes.REACH) / 3
            middles.forEachIndexed { i, middle ->
                assertEquals("bare row at $actual, glyph $i", WidgetSizes.REACH + pitch * (i + 0.5f), middle, 1f)
            }
        }
    }

    /** After the buttons, the cells stand on the frame at the top and the last line's baseline at the foot. */
    @Test fun wordsAfterTheButtonsKeepTheFrame() {
        // A line under the title without a descender, which would reach past its baseline.
        val look = music.copy(subtitle = "Ana Holm", durationMs = 0)
        for (actual in around(WidgetSample.card)) {
            val name = "card, words after, at $actual"
            val drawn = draw(DefaultSlots, look, WidgetSample.card, actual, words = WidgetWordsPlace.After)
            assertFrame(WidgetSizes.PADDING, drawn, name)
            assertCells(drawn, WidgetSizes.PADDING + WidgetSizes.CELL_HEIGHT / 2, 3, name)
        }
    }

    /** Without what plays, the buttons' grid keeps the frame at the sides, the gaps between, in the middle. */
    @Test fun aPadKeepsItsFrameAndGaps() {
        val four = DefaultSlots.take(4)
        val pad = widgetFit(WidgetSample.card.width, WidgetSample.card.height, 4, words = false)
        // Each row's middle, half a row from the widget's.
        val half = (pad.cell + WidgetSizes.GAP) / 2
        for (actual in around(WidgetSample.card)) {
            val name = "pad at $actual"
            val drawn = draw(four, music, WidgetSample.card, actual, showsWords = false)
            val (start, top, end, bottom) = drawn.frame()
            assertEquals("$name start", WidgetSizes.PADDING, start, TOLERANCE)
            assertEquals("$name end", WidgetSizes.PADDING, end, TOLERANCE)
            assertEquals("$name top and bottom", top, bottom, TOLERANCE)
            assertTrue("$name: top $top", top >= WidgetSizes.PADDING - TOLERANCE)
            assertCells(drawn, actual.height / 2 - half, 2, name)
            assertCells(drawn, actual.height / 2 + half, 2, name)
        }
    }

    /** Lower than a cell and its frame, a row's targets keep a touch target's height; its cells keep the frame. */
    @Test fun aLowRowsTargetsKeepTheirHeight() {
        for (size in listOf(WidgetSample.strip, SizeF(156f, 72f))) {
            val setup = WidgetSetup(WidgetStyle.Plain, slots = DefaultSlots.take(3), cells = WidgetCells.All)
            val targets = laidOut(setup, music, size).cells().map { it.height / density }
            println("low row at $size: targets $targets")
            assertTrue("$size: $targets", targets.isNotEmpty())
            assertTrue("$size: $targets", targets.all { it >= WidgetSizes.CELL_MIN - TOLERANCE })
            assertFrame(WidgetSizes.PADDING, draw(DefaultSlots.take(3), music, size), "low row at $size")
        }
    }

    /**
     * A strip of any height keeps its frame: the cover and the cells on it, the words' ink, from the
     * first capital to the baseline, inside it, two lines from 62 dp.
     */
    @Test fun aStripKeepsItsFrame() {
        for (height in 58..90 step 4) {
            val size = SizeF(WidgetSample.strip.width, height.toFloat())
            assertFrame(WidgetSizes.PADDING, draw(DefaultSlots, music, size), "strip at $size")
        }
    }

    @Test fun aPlayerKeepsItsFrameAndGaps() {
        for (actual in around(WidgetSample.player)) {
            val name = "player at $actual"
            val drawn = draw(WidgetSample.ownButtons, WidgetSample.idle, WidgetSample.player, actual)
            assertFrame(WidgetSizes.PADDING, drawn, name)
            assertCells(drawn, actual.height - WidgetSizes.PADDING - WidgetSizes.CELL_HEIGHT / 2, 4, name)
        }
    }

    @Test fun aBarKeepsItsFrameAndGaps() {
        for (actual in around(WidgetSample.bar)) {
            val name = "bar at $actual"
            val drawn = draw(DefaultSlots, WidgetSample.idle, WidgetSample.bar, actual)
            assertFrame(WidgetSizes.PADDING, drawn, name)
        }
    }

    /** Three rows high, the cover, capped at half the width, still stands on the frame at the top. */
    @Test fun aTallPlayerKeepsItsFrame() {
        for (actual in around(WidgetSample.tallPlayer)) {
            val name = "tall player at $actual"
            val drawn = draw(DefaultSlots, WidgetSample.idle, WidgetSample.tallPlayer, actual)
            assertFrame(WidgetSizes.PADDING, drawn, name)
            assertCells(drawn, actual.height - WidgetSizes.PADDING - WidgetSizes.CELL_HEIGHT / 2, 3, name)
        }
    }

    @Test fun aPosterKeepsItsFrameAndGaps() {
        for (actual in around(WidgetSample.tallCard)) {
            val name = "poster at $actual"
            val drawn = draw(DefaultSlots.takeLast(3), WidgetSample.idle, WidgetSample.tallCard, actual)
            assertFrame(WidgetSizes.PADDING, drawn, name)
            assertCells(drawn, actual.height - WidgetSizes.PADDING - WidgetSizes.CELL_HEIGHT / 2, 3, name)
        }
    }

    @Test fun aTilesButtonStandsInTheMiddle() {
        for (actual in around(WidgetSample.tile)) {
            val (start, top, end, bottom) = draw(
                listOf(WidgetSlot(WidgetButton.PlayPause)),
                music,
                WidgetSample.tile,
                actual,
            ).frame()
            assertEquals("tile at $actual", start, end, TOLERANCE)
            assertEquals("tile at $actual", top, bottom, TOLERANCE)
        }
    }

    /** A row without buttons sets what plays in its middle, its first letter on the frame. */
    @Test fun aRowsWordsStandInItsMiddle() {
        // Lines without a descender, which hangs past the baseline the ink stands on.
        val look = music.copy(subtitle = "Ana Holm")
        for (actual in around(WidgetSample.row)) {
            val (start, top, _, bottom) = draw(emptyList(), look, WidgetSample.row, actual).frame()
            assertEquals("row words at $actual", WidgetSizes.PADDING, start, TOLERANCE)
            assertTrue("row words at $actual: $top, $bottom", abs(top - bottom) <= 2f)
        }
    }

    @Test fun largeTypeKeepsTheFrame() {
        RuntimeEnvironment.setFontScale(2f)
        assertFrame(WidgetSizes.PADDING, draw(DefaultSlots, music, WidgetSample.card), "card, 200 %")
        assertFrame(WidgetSizes.PADDING, draw(DefaultSlots, music, WidgetSample.player), "player, 200 %")
    }

    private companion object {
        /** A pixel this far from the ground, red, green and blue together, is ink. */
        const val INKED = 24

        /** Two pixels at xxhdpi: an edge's antialiasing. */
        const val TOLERANCE = 0.67f

        /** Closer than this, two runs of ink are one glyph's parts; glyphs stand twice as far apart. */
        const val GLYPH_PARTS_APART = 10f

        fun distance(a: Int, b: Int) =
            abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
    }
}
