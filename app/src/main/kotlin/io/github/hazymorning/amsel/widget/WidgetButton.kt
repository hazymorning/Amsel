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

    /** The song's heart; gives way where what plays cannot be a favourite. */
    Favourite("favourite", R.string.dock_favorite, R.drawable.ic_favorite),

    /** Gives way, as Repeat does, where what plays is a book, an episode or a station. */
    Shuffle("shuffle", R.string.shuffle, R.drawable.ic_shuffle),
    Repeat("repeat", R.string.player_repeat, R.drawable.ic_repeat),
    Quieter("quieter", R.string.widget_quieter, R.drawable.ic_volume_low),
    Louder("louder", R.string.widget_louder, R.drawable.ic_volume),
    Speakers("speakers", R.string.widget_speakers, R.drawable.ic_speaker),

    /** Opens Play on at its group step. */
    Group("group", R.string.group, R.drawable.ic_group),

    /** Opens Up next, or a book's or an episode's chapters, as Now Playing's last button does. */
    UpNext("up-next", R.string.player_up_next, R.drawable.ic_queue),
    ContinueListening("continue", PlaybackR.string.continue_listening, R.drawable.ic_audiobook),

    /** One item, picked once: its cover is the button, as large as its cell. */
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

/**
 * As a widget of two cells or more comes, as the owner sets theirs: play and next at the end, the
 * speakers before them, and on a wide one shuffle and the heart first.
 */
val DefaultSlots: List<WidgetSlot> = listOf(
    WidgetButton.Shuffle,
    WidgetButton.Favourite,
    WidgetButton.Speakers,
    WidgetButton.PlayPause,
    WidgetButton.Next,
).map(::WidgetSlot)

/** A widget has none to eight buttons; those its size cannot hold give way ([standing]). */
const val MAX_BUTTONS = 8

/**
 * Which of [buttons] stand where only [room] fit: play and pause stays longest, the others give way
 * from the start, so the end, where play and next stand as a widget comes, stays; in their order.
 */
internal fun <T> standing(buttons: List<T>, room: Int, button: (T) -> WidgetButton): List<T> {
    if (buttons.size <= room) return buttons
    if (room <= 0) return emptyList()
    val play = buttons.indexOfFirst { button(it) == WidgetButton.PlayPause }.takeIf { it >= 0 }
    val others = buttons.indices.filter { it != play }.takeLast(if (play != null) room - 1 else room)
    val kept = others.toSet() + listOfNotNull(play)
    return buttons.filterIndexed { i, _ -> i in kept }
}

/** Which buttons stand on a shaded cell: play and pause alone, every one, or none. */
enum class WidgetCells(val id: String, @StringRes val label: Int) {
    Play("play", R.string.widget_cells_play),
    All("all", R.string.widget_cells_all),
    None("none", R.string.widget_cells_none),
    ;

    companion object {
        fun of(id: String?): WidgetCells = entries.firstOrNull { it.id == id } ?: Play
    }
}

/** Where what plays stands: before the buttons (above them, or at the start of a bar), or after them. */
enum class WidgetWordsPosition(val id: String) {
    Before("before"),
    After("after"),
    ;

    companion object {
        fun of(id: String?): WidgetWordsPosition = entries.firstOrNull { it.id == id } ?: Before
    }
}

/**
 * One widget as its person set it: its look, its speaker (null: the one Amsel plays on), its
 * buttons, which of them stand on a cell, and where what plays stands, or that it is left out, the
 * buttons filling the widget; left out, it keeps its place to come back to.
 */
@Immutable
data class WidgetSetup(
    val style: WidgetStyle = WidgetStyle.Cover,
    val speaker: String? = null,
    val slots: List<WidgetSlot> = DefaultSlots,
    val cells: WidgetCells = WidgetCells.Play,
    val words: WidgetWordsPosition = WidgetWordsPosition.Before,
    val showsWords: Boolean = true,
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
