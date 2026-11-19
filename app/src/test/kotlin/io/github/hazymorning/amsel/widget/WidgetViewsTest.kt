package io.github.hazymorning.amsel.widget

import android.content.Context
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.hazymorning.amsel.R
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
    ): View = WidgetSample.plain(context, WidgetSetup(slots = slots), look, size).apply(context, FrameLayout(context))

    private fun View.said(id: Int): CharSequence? = findViewById<View>(id).contentDescription

    private fun View.text(id: Int): String = findViewById<TextView>(id).text.toString()

    private fun View.shows(id: Int): Boolean = findViewById<View>(id)?.visibility == View.VISIBLE

    @Test fun eachButtonSaysWhatItDoes() {
        val widget = drawn(WidgetSample.music)
        assertEquals("Previous", widget.said(R.id.widget_slot_1))
        assertEquals("Pause", widget.said(R.id.widget_slot_2))
        assertEquals("Next", widget.said(R.id.widget_slot_3))
    }

    @Test fun aBooksSkipsAreSeconds() {
        val widget = drawn(WidgetSample.book.copy(playing = true))
        assertEquals("Back 15 seconds", widget.said(R.id.widget_slot_1))
        assertEquals("Forward 30 seconds", widget.said(R.id.widget_slot_3))
    }

    @Test fun aPlayButtonNamesItsItem() {
        val widget = drawn(WidgetSample.music, WidgetSample.ownButtons, SizeF(240f, 240f))
        assertEquals("Quieter", widget.said(R.id.widget_slot_1))
        assertEquals("Continue listening", widget.said(R.id.widget_slot_3))
        assertEquals("Play Blue Hour", widget.said(R.id.widget_slot_4))
    }

    @Test fun theLevelStandsByTheSpeakerWithAVolumeButton() {
        val withVolume = drawn(WidgetSample.music, WidgetSample.ownButtons)
        assertEquals("Kitchen", withVolume.text(R.id.widget_speaker))
        assertEquals(" · 40 %", withVolume.text(R.id.widget_level))
        assertEquals(false, drawn(WidgetSample.music).shows(R.id.widget_level))
    }

    @Test fun playAndPauseWearTheDisc() {
        val widget = drawn(WidgetSample.music)
        assertEquals(true, widget.shows(R.id.widget_disc_2))
        assertEquals(false, widget.shows(R.id.widget_glyph_2))
        assertEquals(false, widget.shows(R.id.widget_disc_1))
    }

    /** A card of two cells holds three buttons: a fourth gives way whole, never cut. */
    @Test fun whatDoesNotFitGivesWayWhole() {
        val widget = drawn(WidgetSample.music, WidgetSample.ownButtons)
        assertEquals(true, widget.shows(R.id.widget_slot_3))
        assertEquals(false, widget.shows(R.id.widget_slot_4))
    }

    @Test fun noButtonsLeaveTheWordsAlone() {
        val widget = drawn(WidgetSample.music, emptyList())
        assertEquals(false, widget.shows(R.id.widget_row))
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
        assertEquals(false, widget.shows(R.id.widget_row))
    }

    @Test fun aFailureStandsWhereTheArtistStood() {
        val widget = drawn(WidgetSample.music.copy(failure = "Kitchen can't be reached."))
        assertEquals("Kitchen can't be reached.", widget.text(R.id.widget_subtitle))
    }

    @Test fun eachSizeTakesItsForm() {
        assertNotNull(drawn(WidgetSample.music, size = WidgetSample.tile).findViewById(R.id.widget_tile_button))
        assertNotNull(drawn(WidgetSample.music, size = WidgetSample.bar).findViewById(R.id.widget_art))
        assertEquals(false, drawn(WidgetSample.music, size = WidgetSample.row).shows(R.id.widget_words))
    }

    /** A one-cell button says what it does as a whole: the tile is the button. */
    @Test fun aTileIsItsButton() {
        val tile = drawn(WidgetSample.music, listOf(WidgetSlot(WidgetButton.PlayPause)), WidgetSample.tile)
        assertEquals("Pause", tile.contentDescription)
    }

    /** WidgetText measures the words as the layout sets them. */
    @Test fun theTypeIsTheLayouts() {
        val widget = drawn(WidgetSample.music.copy(failure = "x"))
        val px = { sp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)
        }
        for ((id, line) in listOf(
            R.id.widget_speaker to WidgetLine.Speaker,
            R.id.widget_title to WidgetLine.Title,
            R.id.widget_subtitle to WidgetLine.Subtitle,
        )) {
            val view = widget.findViewById<TextView>(id)
            assertEquals(px(line.size), view.textSize, 0.5f)
            assertEquals(px(line.line), view.lineHeight.toFloat(), 1f)
            assertEquals(line.weight, view.typeface.weight)
        }
    }
}
