package io.github.hazymorning.amsel.widget

import android.content.Context
import androidx.annotation.DrawableRes
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.RepeatMode
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

            WidgetButton.Favourite -> state(slot.button, look.favourite == true)

            WidgetButton.Shuffle -> state(slot.button, look.shuffle == true)

            WidgetButton.Repeat -> context.getString(
                R.string.widget_state,
                context.getString(slot.button.label),
                context.getString(
                    when (look.repeat) {
                        RepeatMode.All -> R.string.player_repeat_all
                        RepeatMode.One -> R.string.player_repeat_one
                        else -> R.string.player_repeat_off
                    },
                ),
            )

            else -> context.getString(slot.button.label)
        }
    }

    /** What TalkBack says for the widget as a whole: what plays, by whom, and where. */
    fun summary(look: WidgetLook): String {
        if (!look.signedIn) return context.getString(R.string.widget_sign_in)
        val title = look.title ?: return context.getString(R.string.player_not_playing)
        val what = listOfNotNull(title, look.subtitle).joinToString(", ")
        val where = look.speaker.ifBlank { return what }
        val said = if (look.playing) R.string.widget_summary_playing else R.string.widget_summary_paused
        return context.getString(said, what, where)
    }

    private fun state(button: WidgetButton, on: Boolean): String = context.getString(
        R.string.widget_state,
        context.getString(button.label),
        context.getString(if (on) R.string.widget_on else R.string.widget_off),
    )

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
    this == WidgetButton.Favourite && look.favourite == true -> R.drawable.ic_favorite_filled
    this == WidgetButton.Repeat && look.repeat == RepeatMode.One -> R.drawable.ic_repeat_one
    else -> glyph
}

/** A shuffle or repeat that is off shows quieter than one that is on. */
internal fun WidgetButton.isOff(look: WidgetLook): Boolean = when (this) {
    WidgetButton.Shuffle -> look.shuffle != true
    WidgetButton.Repeat -> look.repeat == RepeatMode.Off
    else -> false
}

/** Whether the button has something to act on in what [look] shows; one that has not gives way. */
internal fun WidgetButton.availableIn(look: WidgetLook): Boolean = when (this) {
    WidgetButton.Favourite -> look.favourite != null
    WidgetButton.Shuffle -> look.shuffle != null
    WidgetButton.Repeat -> look.repeat != null
    else -> true
}
