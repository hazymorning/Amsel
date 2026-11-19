package io.github.hazymorning.amsel.widget

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.playback.R as PlaybackR
import kotlinx.serialization.Serializable

/** What one of a widget's four buttons does. Stored by [id]. */
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

/** As a widget comes: the transport, and the speakers at the end. */
val DefaultSlots: List<WidgetSlot> =
    listOf(WidgetButton.Previous, WidgetButton.PlayPause, WidgetButton.Next, WidgetButton.Speakers).map(::WidgetSlot)

/** Four buttons, every widget. */
const val SLOTS = 4
