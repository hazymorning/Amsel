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

    @Test fun playAndPauseShowWhatTheyDo() {
        val playing = drawn(WidgetSample.music)
        assertEquals(true, playing.shows(R.id.widget_glyph_2))
        assertEquals("Pause", playing.said(R.id.widget_slot_2))
        assertEquals("Play", drawn(WidgetSample.music.copy(playing = false)).said(R.id.widget_slot_2))
    }

    /** A card two cells wide holds three buttons: a fourth gives way whole, never cut. */
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
        val shown = { look: WidgetLook ->
            val widget = drawn(look, size = WidgetSample.player)
            (1..MAX_BUTTONS).count { widget.findViewById<View>(slotId(it)).visibility == View.VISIBLE }
        }
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
        assertEquals("Shuffle, on", widget.said(slotId(1)))
        assertEquals("Repeat, One song", widget.said(slotId(2)))
        assertEquals("Favourite, on", widget.said(slotId(3)))
    }

    /** TalkBack hears the widget as a whole before its buttons: what plays, and where. */
    @Test fun theWidgetSaysWhatPlaysWhere() {
        assertEquals(
            "Lanterns, Marlene Vogt. Playing on Kitchen",
            drawn(WidgetSample.music).said(android.R.id.background),
        )
    }

    private fun slotId(n: Int) = SlotIds.of(n - 1).slot

    /** What WidgetText measures is what the layouts set: as they come, and a fifth larger where all has room. */
    @Test fun theTypeIsTheLayouts() {
        val px = { sp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)
        }
        for ((size, scale) in listOf(WidgetSample.card to 1f, SizeF(172f, 260f) to WidgetSizes.LARGE)) {
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
}
