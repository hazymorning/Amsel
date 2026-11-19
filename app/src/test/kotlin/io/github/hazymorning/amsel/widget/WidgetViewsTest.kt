package io.github.hazymorning.amsel.widget

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.github.hazymorning.amsel.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetViewsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun drawn(look: WidgetLook, slots: List<WidgetSlot> = DefaultSlots): View {
        val views = WidgetViews(context).build(1, look, slots, cover = null, thumbs = emptyMap())
        return views.apply(context, FrameLayout(context))
    }

    private fun View.said(id: Int): CharSequence? = findViewById<View>(id).contentDescription

    private fun View.text(id: Int): String = findViewById<TextView>(id).text.toString()

    @Test fun eachButtonSaysWhatItDoes() {
        val widget = drawn(WidgetSample.music)
        assertEquals("Previous", widget.said(R.id.widget_slot_1))
        assertEquals("Pause", widget.said(R.id.widget_slot_2))
        assertEquals("Next", widget.said(R.id.widget_slot_3))
        assertEquals("Speakers", widget.said(R.id.widget_slot_4))
    }

    @Test fun aBooksSkipsAreSeconds() {
        val widget = drawn(WidgetSample.book.copy(playing = true))
        assertEquals("Back 15 seconds", widget.said(R.id.widget_slot_1))
        assertEquals("Forward 30 seconds", widget.said(R.id.widget_slot_3))
    }

    @Test fun aPlayButtonNamesItsItem() {
        val widget = drawn(WidgetSample.music, WidgetSample.ownButtons)
        assertEquals("Quieter", widget.said(R.id.widget_slot_1))
        assertEquals("Continue listening", widget.said(R.id.widget_slot_3))
        assertEquals("Play Blue Hour", widget.said(R.id.widget_slot_4))
    }

    @Test fun theLevelStandsByTheSpeakerWithAVolumeButton() {
        val withVolume = drawn(WidgetSample.music, WidgetSample.ownButtons)
        assertEquals("Kitchen", withVolume.text(R.id.widget_speaker))
        assertEquals(" · 40\u00A0%", withVolume.text(R.id.widget_level))
        assertEquals(View.GONE, drawn(WidgetSample.music).findViewById<View>(R.id.widget_level).visibility)
    }

    @Test fun playAndPauseWearTheDisc() {
        val widget = drawn(WidgetSample.music)
        assertEquals(View.VISIBLE, widget.findViewById<View>(R.id.widget_disc_2).visibility)
        assertEquals(View.GONE, widget.findViewById<View>(R.id.widget_glyph_2).visibility)
        assertEquals(View.GONE, widget.findViewById<View>(R.id.widget_disc_1).visibility)
    }

    @Test fun nothingPlayingSaysSo() {
        val widget = drawn(WidgetSample.idle)
        assertEquals("Bedroom", widget.text(R.id.widget_speaker))
        assertEquals("Not playing", widget.text(R.id.widget_title))
        assertEquals(View.GONE, widget.findViewById<View>(R.id.widget_subtitle).visibility)
    }

    @Test fun signedOutAsksToSignIn() {
        val widget = drawn(WidgetSample.signedOut)
        assertEquals("Sign in to Amsel", widget.text(R.id.widget_title))
        assertEquals(View.GONE, widget.findViewById<View>(R.id.widget_speaker_glyph).visibility)
        assertEquals(View.INVISIBLE, widget.findViewById<View>(R.id.widget_slot_1).visibility)
    }

    @Test fun aFailureStandsWhereTheArtistStood() {
        val widget = drawn(WidgetSample.music.copy(failure = "Kitchen can't be reached."))
        assertEquals("Kitchen can't be reached.", widget.text(R.id.widget_subtitle))
    }
}
