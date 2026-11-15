package io.github.hazymorning.amsel.ui.screens.home

import androidx.annotation.StringRes
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.data.home.SectionLayout
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.data.home.SectionSource
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.model.SpokenWord

/** A section's title: its option in plain words. The layout never appears in it. */
@get:StringRes
internal val SectionOption.title: Int
    get() = when (this) {
        SectionOption.Pinned -> R.string.home_option_pinned
        SectionOption.Everything, SectionOption.PlayedHere -> PlaybackR.string.recently_played
        SectionOption.ContinueListening -> PlaybackR.string.continue_listening
        SectionOption.SmartPlaylists -> R.string.home_option_smart_playlists
        SectionOption.YourPlaylists -> R.string.home_option_your_playlists
        SectionOption.FavoritePlaylists -> R.string.home_option_favorite_playlists
        SectionOption.RecentPlaylists -> R.string.home_option_recent_playlists
        SectionOption.NewAlbums -> R.string.home_option_new_albums
        SectionOption.RecentAlbums -> R.string.home_option_recent_albums
        SectionOption.FavoriteAlbums -> R.string.home_option_favorite_albums
        SectionOption.RandomAlbums -> R.string.home_option_random_albums
        SectionOption.TopArtists -> R.string.home_option_top_artists
        SectionOption.FavoriteArtists -> R.string.home_option_favorite_artists
        SectionOption.RecentArtists -> R.string.home_option_recent_artists
        SectionOption.AllStations -> R.string.radio
        SectionOption.FavoriteStations -> R.string.home_option_favorite_stations
        SectionOption.RecentStations -> R.string.home_option_recent_stations
        SectionOption.NewAudiobooks -> R.string.home_option_new_audiobooks
        SectionOption.NewEpisodes -> R.string.home_option_new_episodes
        SectionOption.YourPodcasts -> R.string.home_option_your_podcasts
        SectionOption.Speakers -> R.string.home_option_speakers
        SectionOption.Search -> R.string.home_option_search
    }

/**
 * The option as the sentence's first chip and its sheet say it. There each name has to say its kind
 * itself: "New albums" under "New in your library", "All stations" under "Radio".
 */
@get:StringRes
internal val SectionOption.choice: Int
    get() = when (this) {
        SectionOption.NewAlbums -> R.string.home_option_new_albums_choice
        SectionOption.AllStations -> R.string.home_option_all_stations_choice
        SectionOption.PlayedHere -> R.string.home_option_played_here_choice
        else -> title
    }

/** The title above the section: "Your albums" while Recently played falls back to them. */
@StringRes
internal fun sectionTitle(option: SectionOption, fallback: Boolean): Int =
    if (fallback && (option == SectionOption.Everything || option == SectionOption.PlayedHere)) {
        R.string.home_your_albums
    } else {
        option.title
    }

/**
 * The option as the sentence's first chip says it, where it stands after "Show": "Show your pins",
 * "Zeigt Angeheftetes". A title is a heading and a sheet's choice a name; the sentence needs its own words.
 */
@get:StringRes
internal val SectionOption.inSentence: Int
    get() = when (this) {
        SectionOption.Pinned -> R.string.home_sentence_option_pinned
        SectionOption.Everything -> R.string.home_sentence_option_everything
        SectionOption.PlayedHere -> R.string.home_sentence_option_played_here
        SectionOption.ContinueListening -> R.string.home_sentence_option_continue_listening
        SectionOption.SmartPlaylists -> R.string.home_sentence_option_smart_playlists
        SectionOption.YourPlaylists -> R.string.home_sentence_option_your_playlists
        SectionOption.FavoritePlaylists -> R.string.home_sentence_option_favorite_playlists
        SectionOption.RecentPlaylists -> R.string.home_sentence_option_recent_playlists
        SectionOption.NewAlbums -> R.string.home_sentence_option_new_albums
        SectionOption.RecentAlbums -> R.string.home_sentence_option_recent_albums
        SectionOption.FavoriteAlbums -> R.string.home_sentence_option_favorite_albums
        SectionOption.RandomAlbums -> R.string.home_sentence_option_random_albums
        SectionOption.TopArtists -> R.string.home_sentence_option_top_artists
        SectionOption.FavoriteArtists -> R.string.home_sentence_option_favorite_artists
        SectionOption.RecentArtists -> R.string.home_sentence_option_recent_artists
        SectionOption.AllStations -> R.string.home_sentence_option_all_stations
        SectionOption.FavoriteStations -> R.string.home_sentence_option_favorite_stations
        SectionOption.RecentStations -> R.string.home_sentence_option_recent_stations
        SectionOption.NewAudiobooks -> R.string.home_sentence_option_new_audiobooks
        SectionOption.NewEpisodes -> R.string.home_sentence_option_new_episodes
        SectionOption.YourPodcasts -> R.string.home_sentence_option_your_podcasts
        SectionOption.Speakers -> R.string.home_sentence_option_speakers
        SectionOption.Search -> R.string.home_sentence_option_search
    }

/** The layout as the sentence's second chip says it, after "as": "as a grid", "als große Cover". */
@get:StringRes
internal val SectionLayout.inSentence: Int
    get() = when (this) {
        SectionLayout.Grid -> R.string.home_sentence_layout_grid
        SectionLayout.Small -> R.string.home_sentence_layout_small
        SectionLayout.Row -> R.string.home_sentence_layout_row
        SectionLayout.Big -> R.string.home_sentence_layout_big
        SectionLayout.List -> R.string.home_sentence_layout_list
    }

@get:StringRes
internal val SectionLayout.label: Int
    get() = when (this) {
        SectionLayout.Grid -> R.string.home_layout_grid
        SectionLayout.Small -> R.string.home_layout_small
        SectionLayout.Row -> R.string.home_layout_row
        SectionLayout.Big -> R.string.home_layout_big
        SectionLayout.List -> R.string.home_layout_list
    }

/**
 * What to show's choices, in the groups and the order its sheet lists them: no name stands over a
 * group, so each holds one kind in one order, what there is, the favourites, the recently played,
 * then the kind's own. Books and podcasts are one group.
 */
internal val WhatGroups: List<List<SectionOption>> = listOf(
    listOf(
        SectionOption.Pinned,
        SectionOption.Everything,
        SectionOption.PlayedHere,
        SectionOption.ContinueListening,
    ),
    listOf(SectionOption.Speakers),
    listOf(SectionOption.Search),
    listOf(
        SectionOption.YourPlaylists,
        SectionOption.FavoritePlaylists,
        SectionOption.RecentPlaylists,
        SectionOption.SmartPlaylists,
    ),
    listOf(
        SectionOption.NewAlbums,
        SectionOption.FavoriteAlbums,
        SectionOption.RecentAlbums,
        SectionOption.RandomAlbums,
    ),
    listOf(SectionOption.FavoriteArtists, SectionOption.RecentArtists, SectionOption.TopArtists),
    listOf(SectionOption.AllStations, SectionOption.FavoriteStations, SectionOption.RecentStations),
    listOf(SectionOption.NewAudiobooks, SectionOption.NewEpisodes, SectionOption.YourPodcasts),
)

/**
 * What to show offers an option for books or podcasts only while the library holds some, and New
 * episodes only where the server keeps that row.
 */
internal fun SectionOption.isOffered(spoken: SpokenWord): Boolean = when {
    this == SectionOption.ContinueListening -> spoken.books || spoken.podcasts
    this == SectionOption.NewEpisodes -> spoken.newEpisodes
    source == SectionSource.Audiobooks -> spoken.books
    source == SectionSource.Podcasts -> spoken.podcasts
    else -> true
}

/**
 * "Show [what] as [how]": the word before each of the sentence's two chips, split from a format
 * string with %1$s and %2$s. The chips end their lines, so nothing follows the second.
 */
internal data class Sentence(val before: String, val between: String) {
    companion object {
        fun of(template: String): Sentence {
            val first = template.indexOf(FIRST)
            val second = template.indexOf(SECOND)
            if (first < 0 || second < first) return Sentence("", template.trim())
            return Sentence(
                template.substring(0, first).trim(),
                template.substring(first + FIRST.length, second).trim(),
            )
        }

        private const val FIRST = "%1\$s"
        private const val SECOND = "%2\$s"
    }
}
