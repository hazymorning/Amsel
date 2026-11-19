package io.github.hazymorning.amsel.widget

import android.content.Context
import androidx.annotation.DrawableRes
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.playback.SKIP_BACK_MS
import io.github.hazymorning.amsel.playback.SKIP_FORWARD_MS

/** What a widget's buttons say and show. */
internal class WidgetSlots(private val context: Context) {
    /** What TalkBack says for a button, and what the widget's page calls it. */
    fun label(slot: WidgetSlot, look: WidgetLook): String {
        val res = context.resources
        return when (slot.button) {
            WidgetButton.PlayPause -> context.getString(if (look.playing) R.string.pause else R.string.play)

            WidgetButton.Next -> if (look.spoken) {
                res.getQuantityString(PlaybackR.plurals.forward_seconds, SKIP_FORWARD_S, SKIP_FORWARD_S)
            } else {
                context.getString(R.string.next)
            }

            WidgetButton.Previous -> if (look.spoken) {
                res.getQuantityString(PlaybackR.plurals.back_seconds, SKIP_BACK_S, SKIP_BACK_S)
            } else {
                context.getString(R.string.previous)
            }

            WidgetButton.PlayItem -> slot.item?.let { context.getString(R.string.widget_play_item, it.title) }
                ?: context.getString(slot.button.label)

            else -> context.getString(slot.button.label)
        }
    }

    private companion object {
        const val SKIP_FORWARD_S = (SKIP_FORWARD_MS / 1000).toInt()
        const val SKIP_BACK_S = (SKIP_BACK_MS / 1000).toInt()
    }
}

/** A button's glyph: a book's skips are by seconds, as on the dock. */
@DrawableRes
internal fun WidgetButton.glyphFor(look: WidgetLook): Int = when {
    this == WidgetButton.PlayPause -> if (look.playing) R.drawable.ic_pause_slim else R.drawable.ic_play
    this == WidgetButton.Next && look.spoken -> R.drawable.ic_skip_forward
    this == WidgetButton.Previous && look.spoken -> R.drawable.ic_skip_back
    else -> glyph
}

/** The views of button [index] in a row: its cell, and its glyph or cover. */
internal class SlotIds(val slot: Int, val glyph: Int, val face: Int) {
    companion object {
        private val All = listOf(
            SlotIds(R.id.widget_slot_1, R.id.widget_glyph_1, R.id.widget_face_1),
            SlotIds(R.id.widget_slot_2, R.id.widget_glyph_2, R.id.widget_face_2),
            SlotIds(R.id.widget_slot_3, R.id.widget_glyph_3, R.id.widget_face_3),
            SlotIds(R.id.widget_slot_4, R.id.widget_glyph_4, R.id.widget_face_4),
        )

        fun of(index: Int): SlotIds = All[index]
    }
}
