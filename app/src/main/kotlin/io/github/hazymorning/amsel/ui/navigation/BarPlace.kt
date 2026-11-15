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

        /** The bar from what is stored ([ids]; null for [Default]): each known place once, Home kept. */
        fun of(ids: List<String>?): List<BarPlace> {
            if (ids == null) return Default
            val places = ids.mapNotNull { id -> entries.firstOrNull { it.id == id } }.distinct()
            return if (Home in places) places else listOf(Home) + places
        }
    }
}
