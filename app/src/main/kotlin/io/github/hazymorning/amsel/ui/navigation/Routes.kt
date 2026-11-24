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

/** Every destination inside the tabs. Now Playing, Up next and sheets are layers, not routes. */
@Serializable
sealed interface Route : NavKey {
    @Serializable data object Home : Route

    @Serializable data object Search : Route

    @Serializable data object Library : Route

    /** One kind of the library alone, as a place of its own in the bar: Albums, Radio. */
    @Serializable data class Shelf(val kind: LibraryKind) : Route

    @Serializable data class Album(val key: String) : Route

    @Serializable data class Artist(val key: String) : Route

    @Serializable data class Playlist(val key: String) : Route

    @Serializable data class Audiobook(val key: String) : Route

    @Serializable data class Podcast(val key: String) : Route

    /** The songs the server finds similar to the song [key], named [title]. */
    @Serializable data class SimilarSongs(val key: String, val title: String) : Route

    /** "See all" from a Home section. */
    @Serializable data class SeeAll(val option: SectionOption, val title: String? = null) : Route

    @Serializable data object Settings : Route

    /** Settings as a place of its own in the bar: its top has no way back, as every place's has none. */
    @Serializable data object SettingsPlace : Route

    /** Which places the bottom bar shows, and in what order. */
    @Serializable data object Bar : Route

    /** Which controls the mini player shows beside what plays, and in what order. */
    @Serializable data object Dock : Route

    /** Which helpful hints Amsel offers over the dock. */
    @Serializable data object Hints : Route

    /** Whether Amsel opens on the speaker that plays, and which speakers it may pick. */
    @Serializable data object Follow : Route

    /** What the Library shows as chips, and in what order. */
    @Serializable data object LibraryKinds : Route

    /** Home's edit mode as a page of its own, from Settings: Done comes back. */
    @Serializable data object HomeEdit : Route

    /** The theme, the language, and what Home, the Library and the bars show. */
    @Serializable data object Appearance : Route

    /** How the sleep timer ends and when it starts by itself. */
    @Serializable data object SleepTimer : Route

    /** Amsel's licence and source code, the privacy notice, and the licences of software by others. */
    @Serializable data object Licences : Route

    /**
     * [route] opened again above itself (an artist, a similar one, the first again), for the
     * [visit]th time: an entry of its own, so the page starts at its top and the one below keeps
     * its place. Screens never see it ([page]).
     */
    @Serializable data class Again(val route: Route, val visit: Int) : Route

    /** The page this entry shows. */
    val page: Route get() = if (this is Again) route else this
}

/**
 * The places the bar can open, each with its own pages: Home, Search, the Library, any kind of the
 * library on its own, and Settings. Unselected their glyph is an outline, selected it is filled.
 */
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

/** What screens may ask of navigation, on their tab's back stack. Back always goes up one level. */
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

/** Opens an item's page; tracks, radio stations and episodes have none: a tap plays them. */
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
 * Resumes a book or an episode where the person stopped, and opens Now Playing when it already
 * plays there. Where it [asks], a bar over the dock offers to resume it on the speaker in use
 * first, also one that rests there; only one playing right now opens Now Playing at once.
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
 * What tapping an item does: its page opens; a radio station plays and an episode resumes, with
 * the tick every play has. Where it [asks] (covers on Home, which look like what opens a page),
 * a station or an episode is offered in a bar over the dock with the speaker it plays on, and
 * plays from its button or a second tap.
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

/** Offers [offer]; a second tap that takes it ticks as every play does. */
private fun OfferController.offerOrTake(offer: Offer, haptics: HapticFeedback) {
    if (takes(offer)) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
    offer(offer)
}

/** [this] offered to play on the speaker in use, with [button]. */
private fun MediaUi.offer(@StringRes button: Int, take: () -> Unit) =
    Offer(about = key, title = title, button = button, art = art, kind = kind, secondTapTakes = true, take = take)

/** Whether [key] plays on the speaker in use right now, not only rests there. */
private fun PlaybackState.plays(key: String): Boolean = nowPlaying.value?.let { it.key == key && it.isPlaying } == true
