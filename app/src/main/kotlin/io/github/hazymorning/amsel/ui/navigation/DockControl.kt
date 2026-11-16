package io.github.hazymorning.amsel.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.hazymorning.amsel.R

/**
 * What the mini player can hold at its end, beside what plays: play and pause always, and as many
 * more as leave the words their room ([MAX] in all), in the person's order (Settings, Mini player).
 * For a book or an episode, next and previous become 30 s forward and 15 s back.
 */
enum class DockControl(val id: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    PlayPause("play", R.string.dock_play, R.drawable.ic_play),
    Next("next", R.string.dock_next, R.drawable.ic_skip_next),
    Previous("previous", R.string.dock_previous, R.drawable.ic_skip_previous),
    Favorite("favorite", R.string.dock_favorite, R.drawable.ic_favorite),
    Speaker("speaker", R.string.dock_speaker, R.drawable.ic_speaker),
    Volume("volume", R.string.player_volume, R.drawable.ic_volume),
    Sleep("sleep", R.string.sleep_timer, R.drawable.ic_sleep),
    Shuffle("shuffle", R.string.shuffle, R.drawable.ic_shuffle),
    ;

    companion object {
        /** Play, and two more at most: past three the song's words would hardly show. */
        const val MAX = 3

        /** The mini player as it comes: play, then next. */
        val Default = Arrangement(entries, setOf(PlayPause, Next))

        /** The controls in the person's order, play always among those shown. */
        fun arrangement(tokens: List<String>?): Arrangement<DockControl> {
            val stored = Arrangement.of(tokens, entries, DockControl::id, Default)
            return stored.copy(shown = stored.shown + PlayPause)
        }
    }
}
