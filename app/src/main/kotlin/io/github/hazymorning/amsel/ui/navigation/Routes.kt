package io.github.hazymorning.amsel.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.Offer
import io.github.hazymorning.amsel.app.OfferController
import io.github.hazymorning.amsel.app.PlaybackState
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.LocalOpenNowPlaying
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.screens.library.LibraryKind
import kotlinx.serialization.Serializable

/** Now Playing, Up next and sheets are layers over the tabs, not routes. */
@Serializable
sealed interface Route : NavKey {
    @Serializable data object Home : Route

    @Serializable data object Search : Route

    @Serializable data object Library : Route

    /** One library kind as its own bar tab. */
    @Serializable data class Shelf(val kind: LibraryKind) : Route

    @Serializable data class Album(val key: String) : Route

    @Serializable data class Artist(val key: String) : Route

    @Serializable data class Playlist(val key: String) : Route

    @Serializable data class Audiobook(val key: String) : Route

    @Serializable data class Podcast(val key: String) : Route

    @Serializable data class SimilarSongs(val key: String, val title: String) : Route

    @Serializable data class SeeAll(val option: SectionOption, val title: String? = null) : Route

    @Serializable data object Settings : Route

    /** Settings as a bar tab's root: no back arrow, as on every tab root. */
    @Serializable data object SettingsPlace : Route

    @Serializable data object Bar : Route

    @Serializable data object Dock : Route

    @Serializable data object Hints : Route

    @Serializable data object Follow : Route

    @Serializable data object LibraryKinds : Route

    /** Home's edit mode opened from Settings as a page of its own; Done goes back. */
    @Serializable data object HomeEdit : Route

    @Serializable data object Appearance : Route

    @Serializable data object SleepTimer : Route

    @Serializable data object Licences : Route

    /**
     * [route] opened again while already on the stack; [visit] counts its earlier entries. A distinct
     * key gives the entry its own saved state: it starts at the top and the earlier one keeps its
     * scroll position. Screens see only [page].
     */
    @Serializable data class Again(val route: Route, val visit: Int) : Route

    val page: Route get() = if (this is Again) route else this
}

enum class Tab(
    val root: Route,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
) {
    Home(Route.Home, R.string.tab_home, R.drawable.ic_home, R.drawable.ic_home_selected),
    Search(Route.Search, R.string.tab_search, R.drawable.ic_search, R.drawable.ic_search_selected),
    Library(Route.Library, PlaybackR.string.library, R.drawable.ic_library, R.drawable.ic_library_selected),
    Playlists(
        Route.Shelf(LibraryKind.Playlists),
        PlaybackR.string.playlists,
        R.drawable.ic_playlist,
        R.drawable.ic_playlist_selected,
    ),
    Albums(Route.Shelf(LibraryKind.Albums), PlaybackR.string.albums, R.drawable.ic_album, R.drawable.ic_album_selected),
    Artists(
        Route.Shelf(LibraryKind.Artists),
        PlaybackR.string.artists,
        R.drawable.ic_artist,
        R.drawable.ic_artist_selected,
    ),
    Radio(Route.Shelf(LibraryKind.Radio), R.string.radio, R.drawable.ic_radio, R.drawable.ic_radio_selected),
    Audiobooks(
        Route.Shelf(LibraryKind.Audiobooks),
        PlaybackR.string.audiobooks,
        R.drawable.ic_audiobook,
        R.drawable.ic_audiobook_selected,
    ),
    Podcasts(
        Route.Shelf(LibraryKind.Podcasts),
        PlaybackR.string.podcasts,
        R.drawable.ic_podcast,
        R.drawable.ic_podcast_selected,
    ),
    Settings(Route.SettingsPlace, R.string.settings, R.drawable.ic_settings, R.drawable.ic_settings_selected),
}

class Navigator(private val stack: NavBackStack<NavKey>, private val closePlayer: () -> Unit) {
    fun open(route: Route) {
        closePlayer()
        val pages = stack.map { (it as? Route)?.page }
        if (pages.lastOrNull() == route) return
        val earlier = pages.count { it == route }
        stack.add(if (earlier == 0) route else Route.Again(route, earlier))
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

/** Tracks, stations and episodes have no page: a tap plays them instead. */
fun Navigator.openMedia(item: MediaUi) {
    when (item.kind) {
        MediaKind.Album -> open(Route.Album(item.key))
        MediaKind.Artist -> open(Route.Artist(item.key))
        MediaKind.Playlist -> open(Route.Playlist(item.key))
        MediaKind.Audiobook -> open(Route.Audiobook(item.key))
        MediaKind.Podcast -> open(Route.Podcast(item.key))
        MediaKind.Track, MediaKind.Radio, MediaKind.Episode -> Unit
    }
}

/**
 * Opens Now Playing when the item already plays. With [asks], an item not playing right now (paused
 * on the speaker included) is first offered in a bar over the dock.
 */
@Composable
fun rememberResume(asks: Boolean = false): (MediaUi) -> Unit {
    val openPlayer = LocalOpenNowPlaying.current
    val graph = LocalContext.current.graph
    val haptics = LocalHapticFeedback.current
    return remember(graph, openPlayer, asks, haptics) {
        { item ->
            val resume = { if (graph.playback.resume(item.key, item.title)) openPlayer() }
            if (asks && !graph.playing.plays(item.key)) {
                graph.offers.offerOrTake(item.offer(R.string.resume, resume), haptics)
            } else {
                resume()
            }
        }
    }
}

/**
 * A station plays and an episode resumes on tap; anything else goes to [openMedia]. [asks] is for covers
 * on Home, where a tap is expected to open a page: there a station or an episode is first offered in a
 * bar over the dock and plays from its button or a second tap.
 */
@Composable
fun rememberOpenItem(navigator: Navigator, asks: Boolean = false): (MediaUi) -> Unit {
    val resume = rememberResume(asks)
    val graph = LocalContext.current.graph
    val openPlayer = LocalOpenNowPlaying.current
    val haptics = LocalHapticFeedback.current
    return remember(navigator, graph, resume, haptics, asks, openPlayer) {
        { item ->
            when (item.kind) {
                MediaKind.Episode -> {
                    if (!asks) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    resume(item)
                }

                MediaKind.Radio -> {
                    val play = { graph.playback.play(listOf(item.key), title = item.title) }
                    when {
                        !asks -> {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            play()
                        }

                        graph.playing.plays(item.key) -> openPlayer()

                        else -> graph.offers.offerOrTake(item.offer(R.string.play, play), haptics)
                    }
                }

                else -> navigator.openMedia(item)
            }
        }
    }
}

/** A second tap on the same item takes the offer, with the haptic tick every play gives. */
private fun OfferController.offerOrTake(offer: Offer, haptics: HapticFeedback) {
    if (takes(offer)) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
    offer(offer)
}

private fun MediaUi.offer(@StringRes button: Int, take: () -> Unit) =
    Offer(about = key, title = title, button = button, art = art, kind = kind, secondTapTakes = true, take = take)

private fun PlaybackState.plays(key: String): Boolean = nowPlaying.value?.let { it.key == key && it.isPlaying } == true
