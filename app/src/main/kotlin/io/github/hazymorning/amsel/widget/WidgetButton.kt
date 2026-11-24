package io.github.hazymorning.amsel.widget

import android.util.SizeF
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.playback.R as PlaybackR
import kotlinx.serialization.Serializable

/** Persisted by [id]: an id never changes. */
enum class WidgetButton(val id: String, @StringRes val label: Int, @DrawableRes val glyph: Int) {
    PlayPause("play", R.string.dock_play, R.drawable.ic_play),
    Next("next", R.string.dock_next, R.drawable.ic_skip_next),
    Previous("previous", R.string.dock_previous, R.drawable.ic_skip_previous),

    Favourite("favourite", R.string.dock_favorite, R.drawable.ic_favorite),

    Shuffle("shuffle", R.string.shuffle, R.drawable.ic_shuffle),
    Repeat("repeat", R.string.player_repeat, R.drawable.ic_repeat),
    Quieter("quieter", R.string.widget_quieter, R.drawable.ic_volume_low),
    Louder("louder", R.string.widget_louder, R.drawable.ic_volume),
    Speakers("speakers", R.string.widget_speakers, R.drawable.ic_speaker),

    Group("group", R.string.group, R.drawable.ic_group),

    UpNext("up-next", R.string.player_up_next, R.drawable.ic_queue),
    ContinueListening("continue", PlaybackR.string.continue_listening, R.drawable.ic_audiobook),

    PlayItem("item", R.string.widget_play_something, R.drawable.ic_album),
    ;

    companion object {
        fun of(id: String): WidgetButton? = entries.firstOrNull { it.id == id }
    }
}

@Immutable
@Serializable
data class WidgetItem(val uri: String, val title: String, val art: String? = null)

@Immutable
data class WidgetSlot(val button: WidgetButton, val item: WidgetItem? = null)

/** Ordered for [standing], which drops buttons from the start: a narrow widget keeps the speakers, play and next. */
val DefaultSlots: List<WidgetSlot> = listOf(
    WidgetButton.Shuffle,
    WidgetButton.Favourite,
    WidgetButton.Speakers,
    WidgetButton.PlayPause,
    WidgetButton.Next,
).map(::WidgetSlot)

const val MAX_BUTTONS = 8

/** The [room] of [buttons] that show, in order: play and pause always, the others dropped from the start. */
internal fun <T> standing(buttons: List<T>, room: Int, button: (T) -> WidgetButton): List<T> {
    if (buttons.size <= room) return buttons
    if (room <= 0) return emptyList()
    val play = buttons.indexOfFirst { button(it) == WidgetButton.PlayPause }.takeIf { it >= 0 }
    val others = buttons.indices.filter { it != play }.takeLast(if (play != null) room - 1 else room)
    val kept = others.toSet() + listOfNotNull(play)
    return buttons.filterIndexed { i, _ -> i in kept }
}

/** Which buttons get a shaded cell. */
enum class WidgetCells(val id: String, @StringRes val label: Int) {
    Play("play", R.string.widget_cells_play),
    All("all", R.string.widget_cells_all),
    None("none", R.string.widget_cells_none),
    ;

    companion object {
        fun of(id: String?): WidgetCells = entries.firstOrNull { it.id == id } ?: Play
    }
}

enum class WidgetWordsPosition(val id: String) {
    Before("before"),
    After("after"),
    ;

    companion object {
        fun of(id: String?): WidgetWordsPosition = entries.firstOrNull { it.id == id } ?: Before
    }
}

/** [speaker] null follows the speaker Amsel plays on; [words] is kept while [showsWords] is false. */
@Immutable
data class WidgetSetup(
    val style: WidgetStyle = WidgetStyle.Cover,
    val speaker: String? = null,
    val slots: List<WidgetSlot> = DefaultSlots,
    val cells: WidgetCells = WidgetCells.Play,
    val words: WidgetWordsPosition = WidgetWordsPosition.Before,
    val showsWords: Boolean = true,
)

enum class WidgetKind(val setup: WidgetSetup, private val width: Float, private val height: Float) {
    Button(WidgetSetup(slots = listOf(WidgetSlot(WidgetButton.PlayPause))), 76f, 76f),

    Buttons(WidgetSetup(), 156f, 76f),

    NowPlaying(WidgetSetup(), 156f, 156f),
    ;

    /** The size in dp until the launcher reports one. */
    val start: SizeF get() = SizeF(width, height)
}
