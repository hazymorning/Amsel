package io.github.hazymorning.amsel.widget

import android.content.Context
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetViewsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun drawn(
        look: WidgetContent,
        slots: List<WidgetSlot> = DefaultSlots,
        size: SizeF = WidgetSample.card,
        setup: WidgetSetup = WidgetSetup(slots = slots),
    ): View = WidgetSample.plain(context, setup, look, size).apply(context, FrameLayout(context))

    /** Button [n], counted from 1. */
    private fun View.button(n: Int): CharSequence? = cells()[n - 1].contentDescription

    private fun View.said(id: Int): CharSequence? = findViewById<View>(id).contentDescription

    private fun View.text(id: Int): String = findViewById<TextView>(id).text.toString()

    private fun View.shows(id: Int): Boolean = findViewById<View>(id)?.visibility == View.VISIBLE

    private fun View.measured(size: SizeF) {
        val px = { dp: Float -> (dp * resources.displayMetrics.density).toInt() }
        measure(
            View.MeasureSpec.makeMeasureSpec(px(size.width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(px(size.height), View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, measuredWidth, measuredHeight)
    }

    @Test fun eachButtonSaysWhatItDoes() {
        val widget = drawn(WidgetSample.music)
        assertEquals("Speakers", widget.button(1))
        assertEquals("Pause", widget.button(2))
        assertEquals("Next", widget.button(3))
    }

    @Test fun aBooksSkipsAreSeconds() {
        val transport = listOf(WidgetButton.Previous, WidgetButton.PlayPause, WidgetButton.Next).map(::WidgetSlot)
        val widget = drawn(WidgetSample.book.copy(playing = true), transport)
        assertEquals("Back 15 seconds", widget.button(1))
        assertEquals("Forward 30 seconds", widget.button(3))
    }

    @Test fun playAndTheEndStayWhereFewFit() {
        val shown = { size: SizeF -> drawn(WidgetSample.music, size = size).cells().map { it.contentDescription } }
        assertEquals(listOf("Speakers", "Pause", "Next"), shown(WidgetSample.row))
        // The owner's phone: two cells are 162 dp wide.
        assertEquals(listOf("Pause", "Next"), shown(SizeF(162f, 96f)))
        assertEquals(listOf("Shuffle, off", "Favourite, on", "Speakers", "Pause", "Next"), shown(WidgetSample.player))
        assertEquals("Pause", drawn(WidgetSample.music, size = WidgetSample.tile).contentDescription)
    }

    @Test fun aTallWidgetsButtonsTakeTwoRows() {
        val cells = drawn(WidgetSample.music, size = WidgetSample.tallCard).cells()
        assertEquals(5, cells.size)
        assertEquals(cells[0].parent, cells[2].parent)
        assertEquals(cells[3].parent, cells[4].parent)
        assertEquals(false, cells[2].parent == cells[3].parent)
    }

    @Test fun upNextNamesWhatItOpens() {
        val slots = listOf(WidgetSlot(WidgetButton.UpNext))
        assertEquals("Up next", drawn(WidgetSample.music, slots).button(1))
        assertEquals("Chapters", drawn(WidgetSample.book, slots).button(1))
    }

    @Test fun aPlayButtonNamesItsItem() {
        val widget = drawn(WidgetSample.music, WidgetSample.ownButtons, SizeF(240f, 240f))
        assertEquals("Quieter", widget.button(1))
        assertEquals("Continue listening", widget.button(3))
        assertEquals("Play Blue Hour", widget.button(4))
    }

    @Test fun theLevelStandsAtTheEndOfTheSpeakersLine() {
        val widget = drawn(WidgetSample.music)
        assertEquals("Kitchen", widget.text(R.id.widget_speaker))
        assertEquals("40", widget.text(R.id.widget_level))
        assertEquals(true, widget.shows(R.id.widget_level_glyph))
        assertEquals("silent", drawn(WidgetSample.music.copy(volume = 0)).text(R.id.widget_level))
        val phone = drawn(WidgetSample.music.copy(volume = null))
        assertEquals(false, phone.shows(R.id.widget_level))
        assertEquals(false, phone.shows(R.id.widget_level_glyph))
    }

    @Test fun aLongNameIsCutBeforeTheLevel() {
        val look = WidgetSample.music.copy(speaker = "The long room downstairs by the garden door")
        val widget = drawn(look).apply { measured(WidgetSample.card) }
        val level = widget.findViewById<TextView>(R.id.widget_level)
        assertEquals(0, level.layout.getEllipsisCount(0))
        assertEquals(true, widget.findViewById<TextView>(R.id.widget_speaker).layout.getEllipsisCount(0) > 0)
    }

    @Test fun playAndPauseShowWhatTheyDo() {
        val playing = drawn(WidgetSample.music)
        assertEquals(true, playing.cells()[1].shows(R.id.widget_glyph))
        assertEquals("Pause", playing.button(2))
        assertEquals("Play", drawn(WidgetSample.music.copy(playing = false)).button(2))
    }

    @Test fun whatDoesNotFitGivesWayWhole() {
        val widget = drawn(WidgetSample.music, WidgetSample.ownButtons)
        assertEquals(3, widget.cells().size)
    }

    @Test fun noButtonsLeaveTheWordsAlone() {
        val widget = drawn(WidgetSample.music, emptyList())
        assertEquals(0, widget.cells().size)
        assertEquals(3, widget.findViewById<TextView>(R.id.widget_title).maxLines)
    }

    @Test fun nothingPlayingSaysSo() {
        val widget = drawn(WidgetSample.idle)
        assertEquals("Bedroom", widget.text(R.id.widget_speaker))
        assertEquals("Not playing", widget.text(R.id.widget_title))
        assertEquals(false, widget.shows(R.id.widget_subtitle))
    }

    @Test fun signedOutAsksToSignIn() {
        val widget = drawn(WidgetSample.signedOut)
        assertEquals("Sign in to Amsel", widget.text(R.id.widget_title))
        assertEquals(false, widget.shows(R.id.widget_speaker_glyph))
        assertEquals(0, widget.cells().size)
    }

    @Test fun aFailureStandsWhereTheArtistStood() {
        val widget = drawn(WidgetSample.music.copy(failure = "Kitchen can't be reached."))
        assertEquals("Kitchen can't be reached.", widget.text(R.id.widget_subtitle))
    }

    @Test fun eachSizeTakesItsForm() {
        assertNotNull(drawn(WidgetSample.music, size = WidgetSample.tile).findViewById(R.id.widget_tile_cell))
        assertNotNull(drawn(WidgetSample.music, size = WidgetSample.bar).findViewById(R.id.widget_art))
        assertEquals(false, drawn(WidgetSample.music, size = WidgetSample.row).shows(R.id.widget_words))
    }

    @Test fun aTileIsItsButton() {
        val tile = drawn(WidgetSample.music, listOf(WidgetSlot(WidgetButton.PlayPause)), WidgetSample.tile)
        assertEquals("Pause", tile.contentDescription)
    }

    @Test fun buttonsWithNothingToActOnGiveWay() {
        val shown = { look: WidgetContent -> drawn(look, size = WidgetSample.player).cells().size }
        assertEquals(5, shown(WidgetSample.music))
        assertEquals(3, shown(WidgetSample.book))
    }

    @Test fun shuffleAndRepeatSayWhetherTheyAreOn() {
        val slots =
            listOf(
                WidgetSlot(WidgetButton.Shuffle),
                WidgetSlot(WidgetButton.Repeat),
                WidgetSlot(WidgetButton.Favourite),
            )
        val widget = drawn(WidgetSample.music.copy(shuffle = true, repeat = RepeatMode.One), slots)
        assertEquals("Shuffle, on", widget.button(1))
        assertEquals("Repeat, One song", widget.button(2))
        assertEquals("Favourite, on", widget.button(3))
    }

    @Test fun theWidgetSaysWhatPlaysWhere() {
        assertEquals(
            "Lanterns, Marlene Vogt. Playing on Kitchen. Volume, 40\u00A0%",
            drawn(WidgetSample.music).said(android.R.id.background),
        )
    }

    /** WidgetLine and WordSize match the text sizes the three words layouts set. */
    @Test fun theTypeIsTheLayouts() {
        val px = { sp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)
        }
        val sizes = listOf(
            WidgetSample.card to WordSize.Regular.scale,
            SizeF(172f, 260f) to WordSize.Large.scale,
            WidgetSample.strip to WordSize.Small.scale,
        )
        for ((size, scale) in sizes) {
            // One row of buttons, so a tall card's height goes to the text.
            val widget = drawn(WidgetSample.music.copy(failure = "x"), DefaultSlots.takeLast(3), size)
            for ((id, line) in listOf(
                R.id.widget_speaker to WidgetLine.Speaker,
                R.id.widget_title to WidgetLine.Title,
                R.id.widget_subtitle to WidgetLine.Subtitle,
                R.id.widget_time_still to WidgetLine.Subtitle,
            )) {
                val view = widget.findViewById<TextView>(id)
                assertEquals("$size", px(line.size * scale), view.textSize, 0.5f)
                assertEquals("$size", px(line.line * scale), view.lineHeight.toFloat(), 1f)
                assertEquals("$size", line.weight, view.typeface.weight)
            }
        }
    }

    @Test fun whatPlaysCanFollowTheButtons() {
        val widget = drawn(WidgetSample.music, setup = WidgetSetup(words = WidgetWordsPosition.After))
        assertEquals(true, widget.shows(R.id.widget_cells_start))
        assertEquals(false, widget.shows(R.id.widget_cells_end))
        assertEquals(3, widget.cells().size)
    }

    @Test fun withoutWhatPlaysTheButtonsFillTheWidget() {
        val four = DefaultSlots.take(4)
        val widget = drawn(WidgetSample.music, setup = WidgetSetup(slots = four, showsWords = false))
        assertEquals(false, widget.shows(R.id.widget_words))
        val cells = widget.cells()
        assertEquals(4, cells.size)
        assertEquals(cells[0].parent, cells[1].parent)
        assertEquals(false, cells[1].parent == cells[2].parent)
    }

    @Test fun aStripKeepsTheSpeakerOverTheTitle() {
        val widget = drawn(WidgetSample.music, size = WidgetSample.strip)
        assertEquals(true, widget.shows(R.id.widget_speaker_row))
        assertEquals("Lanterns", widget.text(R.id.widget_title))
    }

    @Test fun signedOutAWidgetOfButtonsAsksToSignIn() {
        val widget = drawn(WidgetSample.signedOut, setup = WidgetSetup(showsWords = false))
        assertEquals("Sign in to Amsel", widget.text(R.id.widget_title))
    }
}
