package io.github.hazymorning.amsel.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import io.github.hazymorning.amsel.MainActivity
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.playback.SKIP_BACK_MS
import io.github.hazymorning.amsel.playback.SKIP_FORWARD_MS
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.navigation.Opening

/** Words and glyphs over a light cover (or none) and over a dark one; at night both are the night's. */
private enum class Tone(
    @ColorRes val words: Int,
    @ColorRes val quiet: Int,
    @ColorRes val glyph: Int,
    @ColorRes val onDisc: Int,
    @DrawableRes val disc: Int,
    @DrawableRes val press: Int,
    @DrawableRes val veil: Int,
) {
    Light(
        R.color.widget_words_light,
        R.color.widget_quiet_light,
        R.color.widget_glyph_light,
        R.color.widget_on_disc_light,
        R.drawable.widget_disc_light,
        R.drawable.widget_press_light,
        R.drawable.widget_veil_light,
    ),
    Dark(
        R.color.widget_words_dark,
        R.color.widget_quiet_dark,
        R.color.widget_glyph_dark,
        R.color.widget_on_disc_dark,
        R.drawable.widget_disc_dark,
        R.drawable.widget_press_dark,
        R.drawable.widget_veil_dark,
    ),
}

/**
 * A widget's views: [look] over its cover, and its four [slots]. Colours go by resource where the
 * launcher resolves them (Android 12 and up), so its day and night switch them without Amsel.
 */
class WidgetViews(private val context: Context) {
    /** [taps] false for the setup page's picture, whose buttons choose rather than act. */
    fun build(
        widgetId: Int,
        look: WidgetLook,
        slots: List<WidgetSlot>,
        cover: WidgetCover?,
        thumbs: Map<String, Bitmap>,
        taps: Boolean = true,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget)
        val shown = cover?.takeIf { look.signedIn && look.title != null }
        val tone = if (shown == null || shown.light) Tone.Light else Tone.Dark
        if (shown != null) {
            views.setImageViewBitmap(R.id.widget_cover, shown.bitmap)
            views.setInt(R.id.widget_veil, "setBackgroundResource", tone.veil)
        }
        val coverShows = if (shown != null) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.widget_cover, coverShows)
        views.setViewVisibility(R.id.widget_veil, coverShows)

        views.setTextViewText(R.id.widget_speaker, look.speaker.ifBlank { context.getString(R.string.app_name) })
        views.setViewVisibility(R.id.widget_speaker_glyph, if (look.signedIn) View.VISIBLE else View.GONE)
        val level = level(look, slots)
        views.setTextViewText(R.id.widget_level, level.orEmpty())
        views.setViewVisibility(R.id.widget_level, if (level == null) View.GONE else View.VISIBLE)
        views.setTextViewText(
            R.id.widget_title,
            when {
                !look.signedIn -> context.getString(R.string.widget_sign_in)
                else -> look.title ?: context.getString(R.string.player_not_playing)
            },
        )
        val subtitle = (look.failure ?: look.subtitle).takeIf { look.signedIn && look.title != null }
        views.setTextViewText(R.id.widget_subtitle, subtitle.orEmpty())
        views.setViewVisibility(R.id.widget_subtitle, if (subtitle == null) View.GONE else View.VISIBLE)
        views.textColor(R.id.widget_speaker, tone.words)
        views.textColor(R.id.widget_level, tone.quiet)
        views.textColor(R.id.widget_title, tone.words)
        views.textColor(R.id.widget_subtitle, tone.quiet)
        views.glyphColor(R.id.widget_speaker_glyph, tone.glyph)

        slots.take(SLOTS).forEachIndexed { index, slot ->
            button(views, widgetId, index, slot, look, tone, thumbs, taps)
        }
        if (taps) {
            val opening = if (look.signedIn) Opening.NowPlaying else null
            views.setOnClickPendingIntent(android.R.id.background, open(opening))
        }
        return views
    }

    /** The speaker's level, after its name, where Quieter or Louder is among the buttons. */
    private fun level(look: WidgetLook, slots: List<WidgetSlot>): String? {
        val volumeButton = slots.any { it.button == WidgetButton.Quieter || it.button == WidgetButton.Louder }
        val level = look.volume?.takeIf { volumeButton && look.signedIn } ?: return null
        return DOT_SEPARATOR + context.getString(R.string.player_volume_percent, level)
    }

    private fun button(
        views: RemoteViews,
        widgetId: Int,
        index: Int,
        slot: WidgetSlot,
        look: WidgetLook,
        tone: Tone,
        thumbs: Map<String, Bitmap>,
        taps: Boolean,
    ) {
        val ids = SlotIds.of(index)
        views.setInt(ids.slot, "setBackgroundResource", tone.press)
        views.setViewVisibility(ids.slot, if (look.signedIn) View.VISIBLE else View.INVISIBLE)
        val thumb = slot.item?.art?.let(thumbs::get).takeIf { slot.button == WidgetButton.PlayItem }
        val disc = slot.button == WidgetButton.PlayPause
        views.setViewVisibility(ids.disc, if (disc) View.VISIBLE else View.GONE)
        views.setViewVisibility(ids.thumb, if (thumb != null) View.VISIBLE else View.GONE)
        views.setViewVisibility(ids.glyph, if (!disc && thumb == null) View.VISIBLE else View.GONE)
        when {
            disc -> {
                views.setInt(ids.disc, "setBackgroundResource", tone.disc)
                views.setImageViewResource(ids.disc, if (look.playing) R.drawable.ic_pause_slim else R.drawable.ic_play)
                views.glyphColor(ids.disc, tone.onDisc)
            }

            thumb != null -> views.setImageViewBitmap(ids.thumb, thumb)

            else -> {
                views.setImageViewResource(ids.glyph, glyph(slot.button, look.spoken))
                views.glyphColor(ids.glyph, tone.glyph)
            }
        }
        views.setContentDescription(ids.slot, label(slot, look))
        if (!taps) return
        val press = if (slot.button == WidgetButton.Speakers) {
            open(Opening.Speakers)
        } else {
            WidgetTaps.pending(context, widgetId, index, shownPlaying = look.playing)
        }
        views.setOnClickPendingIntent(ids.slot, press)
    }

    /** What TalkBack says for a button, and what the setup page calls it. */
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

    @DrawableRes
    private fun glyph(button: WidgetButton, spoken: Boolean): Int = when {
        button == WidgetButton.Next && spoken -> R.drawable.ic_skip_forward
        button == WidgetButton.Previous && spoken -> R.drawable.ic_skip_back
        else -> button.glyph
    }

    /** Amsel itself, at [opening]: Now Playing, the speakers, or where it opens anyway. */
    private fun open(opening: Opening?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (opening != null) intent.setAction(Intent.ACTION_VIEW).setData(opening.link)
        return PendingIntent.getActivity(
            context,
            opening?.ordinal ?: -1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun RemoteViews.textColor(id: Int, @ColorRes color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setColorStateList(id, "setTextColor", color)
        } else {
            setTextColor(id, context.getColor(color))
        }
    }

    private fun RemoteViews.glyphColor(id: Int, @ColorRes color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setColorStateList(id, "setImageTintList", color)
        } else {
            setInt(id, "setColorFilter", context.getColor(color))
        }
    }

    private companion object {
        const val SKIP_FORWARD_S = (SKIP_FORWARD_MS / 1000).toInt()
        const val SKIP_BACK_S = (SKIP_BACK_MS / 1000).toInt()
    }
}

/** The views of button [index]: its ground, and its glyph, play disc or cover. */
private class SlotIds(val slot: Int, val glyph: Int, val disc: Int, val thumb: Int) {
    companion object {
        private val All = listOf(
            SlotIds(R.id.widget_slot_1, R.id.widget_glyph_1, R.id.widget_disc_1, R.id.widget_thumb_1),
            SlotIds(R.id.widget_slot_2, R.id.widget_glyph_2, R.id.widget_disc_2, R.id.widget_thumb_2),
            SlotIds(R.id.widget_slot_3, R.id.widget_glyph_3, R.id.widget_disc_3, R.id.widget_thumb_3),
            SlotIds(R.id.widget_slot_4, R.id.widget_glyph_4, R.id.widget_disc_4, R.id.widget_thumb_4),
        )

        fun of(index: Int): SlotIds = All[index]
    }
}
