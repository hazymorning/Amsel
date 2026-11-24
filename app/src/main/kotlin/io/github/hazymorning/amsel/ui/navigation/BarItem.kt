package io.github.hazymorning.amsel.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.playback.R as PlaybackR

/**
 * [Speaker] has no tab: it opens Play on. Home always shows, as back navigation ends there; without
 * [Settings] in the bar, a pull down at the top of Home opens them.
 */
enum class BarItem(val id: String, @StringRes val label: Int, @DrawableRes val icon: Int, val tab: Tab?) {
    Home("home", R.string.tab_home, R.drawable.ic_home, Tab.Home),
    Speaker("speaker", R.string.bar_speaker, R.drawable.ic_speaker, null),
    Search("search", R.string.tab_search, R.drawable.ic_search, Tab.Search),
    Library("library", PlaybackR.string.library, R.drawable.ic_library, Tab.Library),
    Playlists("playlists", PlaybackR.string.playlists, R.drawable.ic_playlist, Tab.Playlists),
    Albums("albums", PlaybackR.string.albums, R.drawable.ic_album, Tab.Albums),
    Artists("artists", PlaybackR.string.artists, R.drawable.ic_artist, Tab.Artists),
    Radio("radio", R.string.radio, R.drawable.ic_radio, Tab.Radio),
    Audiobooks("audiobooks", PlaybackR.string.audiobooks, R.drawable.ic_audiobook, Tab.Audiobooks),
    Podcasts("podcasts", PlaybackR.string.podcasts, R.drawable.ic_podcast, Tab.Podcasts),
    Settings("settings", R.string.settings, R.drawable.ic_settings, Tab.Settings),
    ;

    companion object {
        /** More than five makes each item too narrow to recognise by its icon. */
        const val MAX = 5

        val Default: List<BarItem> = listOf(Home, Speaker, Search, Library, Settings)

        fun arrangement(tokens: List<String>?): OrderedToggles<BarItem> {
            val defaults = OrderedToggles(Default + (entries - Default.toSet()), Default.toSet())
            val stored = OrderedToggles.of(tokens, entries, BarItem::id, defaults)
            // A stored bar without Home gets it back at the front.
            val homeless = tokens != null && tokens.none { it.removePrefix(OrderedToggles.HIDDEN) == Home.id }
            val order = if (homeless) listOf(Home) + (stored.order - Home) else stored.order
            // Bars stored before Settings could be in the bar get it at the end, if fewer than MAX are shown.
            val settingsNew = tokens != null && tokens.none { it.removePrefix(OrderedToggles.HIDDEN) == Settings.id }
            val shown = stored.shown + Home
            return OrderedToggles(order, if (settingsNew && shown.size < MAX) shown + Settings else shown)
        }

        fun of(tokens: List<String>?): List<BarItem> = arrangement(tokens).visible
    }
}
