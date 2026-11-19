package io.github.hazymorning.amsel.widget

import android.util.SizeF
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.playback.R as PlaybackR
import kotlinx.serialization.Serializable

/** What one of a widget's buttons does. Stored by [id]. */
enum class WidgetButton(val id: String, @StringRes val label: Int, @DrawableRes val glyph: Int) {
    PlayPause("play", R.string.dock_play, R.drawable.ic_play),
    Next("next", R.string.dock_next, R.drawable.ic_skip_next),
    Previous("previous", R.string.dock_previous, R.drawable.ic_skip_previous),
    Quieter("quieter", R.string.widget_quieter, R.drawable.ic_volume_low),
    Louder("louder", R.string.widget_louder, R.drawable.ic_volume),
    Speakers("speakers", R.string.widget_speakers, R.drawable.ic_speaker),
    ContinueListening("continue", PlaybackR.string.continue_listening, R.drawable.ic_audiobook),

    /** One item, picked once: its cover is the button. */
    PlayItem("item", R.string.widget_play_something, R.drawable.ic_album),
    ;

    companion object {
        fun of(id: String): WidgetButton? = entries.firstOrNull { it.id == id }
    }
}

/** The album, playlist, artist, station, song, book or podcast a [WidgetButton.PlayItem] plays. */
@Immutable
@Serializable
data class WidgetItem(val uri: String, val title: String, val art: String? = null)

@Immutable
data class WidgetSlot(val button: WidgetButton, val item: WidgetItem? = null)

/** As a widget of two cells or more comes: the transport. */
val DefaultSlots: List<WidgetSlot> =
    listOf(WidgetButton.Previous, WidgetButton.PlayPause, WidgetButton.Next).map(::WidgetSlot)

/** A widget has none to four buttons; those its size cannot hold give way from the end. */
const val MAX_BUTTONS = 4

/** One widget as its person set it: its look, its speaker (null: the one Amsel plays on), its buttons. */
@Immutable
data class WidgetSetup(
    val style: WidgetStyle = WidgetStyle.Cover,
    val speaker: String? = null,
    val slots: List<WidgetSlot> = DefaultSlots,
)

/**
 * The widget's three ways onto the home screen, by the size it starts at, about [width] by [height] dp
 * on a usual phone; each changes form with its size.
 */
enum class WidgetKind(val setup: WidgetSetup, private val width: Float, private val height: Float) {
    /** One cell, one button: play and pause as it comes. */
    Button(WidgetSetup(slots = listOf(WidgetSlot(WidgetButton.PlayPause))), 76f, 76f),

    /** Two cells, one row: the transport. */
    Buttons(WidgetSetup(), 156f, 76f),

    /** Two cells by two: what plays over the transport. */
    NowPlaying(WidgetSetup(), 156f, 156f),
    ;

    /** Its size until the launcher says. */
    val start: SizeF get() = SizeF(width, height)
}
