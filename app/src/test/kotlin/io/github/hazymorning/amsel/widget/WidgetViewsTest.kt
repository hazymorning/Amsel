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
        look: WidgetLook,
        slots: List<WidgetSlot> = DefaultSlots,
        size: SizeF = WidgetSample.card,
        setup: WidgetSetup = WidgetSetup(slots = slots),
    ): View = WidgetSample.plain(context, setup, look, size).apply(context, FrameLayout(context))

    /** What button [n], counted from 1, says. */
    private fun View.button(n: Int): CharSequence? = cells()[n - 1].contentDescription

    private fun View.said(id: Int): CharSequence? = findViewById<View>(id).contentDescription

    private fun View.text(id: Int): String = findViewById<TextView>(id).text.toString()

    private fun View.shows(id: Int): Boolean = findViewById<View>(id)?.visibility == View.VISIBLE

    @Test fun eachButtonSaysWhatItDoes() {
        val widget = drawn(WidgetSample.music)
        assertEquals("Previous", widget.button(1))
        assertEquals("Pause", widget.button(2))
        assertEquals("Next", widget.button(3))
    }

    @Test fun aBooksSkipsAreSeconds() {
        val widget = drawn(WidgetSample.book.copy(playing = true))
        assertEquals("Back 15 seconds", widget.button(1))
        assertEquals("Forward 30 seconds", widget.button(3))
    }

    @Test fun aPlayButtonNamesItsItem() {
        val widget = drawn(WidgetSample.music, WidgetSample.ownButtons, SizeF(240f, 240f))
        assertEquals("Quieter", widget.button(1))
        assertEquals("Continue listening", widget.button(3))
        assertEquals("Play Blue Hour", widget.button(4))
    }

    @Test fun theLevelStandsByTheSpeakerWithAVolumeButton() {
        val withVolume = drawn(WidgetSample.music, WidgetSample.ownButtons)
        assertEquals("Kitchen", withVolume.text(R.id.widget_speaker))
        assertEquals(" · 40 %", withVolume.text(R.id.widget_level))
        assertEquals(false, drawn(WidgetSample.music).shows(R.id.widget_level))
    }

    @Test fun playAndPauseShowWhatTheyDo() {
        val playing = drawn(WidgetSample.music)
        assertEquals(true, playing.cells()[1].shows(R.id.widget_glyph))
        assertEquals("Pause", playing.button(2))
        assertEquals("Play", drawn(WidgetSample.music.copy(playing = false)).button(2))
    }

    /** A card two cells wide holds three buttons: a fourth gives way whole, never cut. */
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

    /** A one-cell button says what it does as a whole: the tile is the button. */
    @Test fun aTileIsItsButton() {
        val tile = drawn(WidgetSample.music, listOf(WidgetSlot(WidgetButton.PlayPause)), WidgetSample.tile)
        assertEquals("Pause", tile.contentDescription)
    }

    /** A book has no heart and no shuffle: the transport stands alone and evenly where they were. */
    @Test fun buttonsWithNothingToActOnGiveWay() {
        val shown = { look: WidgetLook -> drawn(look, size = WidgetSample.player).cells().size }
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

    /** TalkBack hears the widget as a whole before its buttons: what plays, and where. */
    @Test fun theWidgetSaysWhatPlaysWhere() {
        assertEquals(
            "Lanterns, Marlene Vogt. Playing on Kitchen",
            drawn(WidgetSample.music).said(android.R.id.background),
        )
    }

    /** What WidgetText measures is what the layouts set: as they come, smaller in a strip, larger where all has room. */
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
            val widget = drawn(WidgetSample.music.copy(failure = "x"), size = size)
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

    /** After the buttons, what plays stands at the foot, the cells at the top. */
    @Test fun whatPlaysCanFollowTheButtons() {
        val widget = drawn(WidgetSample.music, setup = WidgetSetup(words = WidgetWordsPlace.After))
        assertEquals(true, widget.shows(R.id.widget_cells_start))
        assertEquals(false, widget.shows(R.id.widget_cells_end))
        assertEquals(3, widget.cells().size)
    }

    /** Left out, what plays gives the buttons the widget: four in two rows of two. */
    @Test fun withoutWhatPlaysTheButtonsFillTheWidget() {
        val four = DefaultSlots.take(4)
        val widget = drawn(WidgetSample.music, setup = WidgetSetup(slots = four, showsWords = false))
        assertEquals(false, widget.shows(R.id.widget_words))
        val cells = widget.cells()
        assertEquals(4, cells.size)
        assertEquals(cells[0].parent, cells[1].parent)
        assertEquals(false, cells[1].parent == cells[2].parent)
    }

    /** In a strip lower than the speaker's line and the title at their size, both stay, smaller. */
    @Test fun aStripKeepsTheSpeakerOverTheTitle() {
        val widget = drawn(WidgetSample.music, size = WidgetSample.strip)
        assertEquals(true, widget.shows(R.id.widget_speaker_row))
        assertEquals("Lanterns", widget.text(R.id.widget_title))
    }

    /** Signed out, a widget without what plays still asks to sign in. */
    @Test fun signedOutAWidgetOfButtonsAsksToSignIn() {
        val widget = drawn(WidgetSample.signedOut, setup = WidgetSetup(showsWords = false))
        assertEquals("Sign in to Amsel", widget.text(R.id.widget_title))
    }
}
