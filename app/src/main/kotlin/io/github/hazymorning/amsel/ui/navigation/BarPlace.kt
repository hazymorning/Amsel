package io.github.hazymorning.amsel.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.playback.R as PlaybackR

/**
 * What the bottom bar can hold: the three tabs and the speaker's button, which opens Play on. The
 * person chooses which it shows and in what order (Settings, Bottom bar); Home always stays, for
 * Settings and the way back are under it.
 */
enum class BarPlace(val id: String, @StringRes val label: Int, @DrawableRes val icon: Int, val tab: Tab?) {
    Home("home", R.string.tab_home, R.drawable.ic_home, Tab.Home),
    Speaker("speaker", R.string.bar_speaker, R.drawable.ic_speaker, null),
    Search("search", R.string.tab_search, R.drawable.ic_search, Tab.Search),
    Library("library", PlaybackR.string.library, R.drawable.ic_library, Tab.Library),
    ;

    companion object {
        /** The bar as it comes: every place, in this order. */
        val Default: List<BarPlace> = entries

        /** Every place in the person's order, those in the bar shown, Home always among them. */
        fun arrangement(tokens: List<String>?): Arrangement<BarPlace> {
            val stored = Arrangement.of(tokens, entries, BarPlace::id, Arrangement(entries, entries.toSet()))
            // A stored bar without Home gets it back first, where the way back belongs.
            val homeless = tokens != null && tokens.none { it.removePrefix(Arrangement.HIDDEN) == Home.id }
            val order = if (homeless) listOf(Home) + (stored.order - Home) else stored.order
            return Arrangement(order, stored.shown + Home)
        }

        /** The bar from what is stored ([tokens]; null for [Default]): the places shown, in order. */
        fun of(tokens: List<String>?): List<BarPlace> = arrangement(tokens).visible
    }
}
