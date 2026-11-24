package io.github.hazymorning.amsel.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.hazymorning.amsel.R

/** For a book or an episode, [Next] and [Previous] skip forward and back in time instead. */
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
        /** More than three leaves too little width for the title and artist. */
        const val MAX = 3

        val Default = OrderedToggles(entries, setOf(PlayPause, Next))

        fun arrangement(tokens: List<String>?): OrderedToggles<DockControl> {
            val stored = OrderedToggles.of(tokens, entries, DockControl::id, Default)
            return stored.copy(shown = stored.shown + PlayPause)
        }
    }
}
