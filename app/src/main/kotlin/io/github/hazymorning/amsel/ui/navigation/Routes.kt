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
import io.github.hazymorning.amsel.app.Offers
import io.github.hazymorning.amsel.app.Playing
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.LocalOpenNowPlaying
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import kotlinx.serialization.Serializable

/** Every destination inside the three tabs. Now Playing, Up next and sheets are layers, not routes. */
@Serializable
sealed interface Route : NavKey {
    @Serializable data object Home : Route

    @Serializable data object Search : Route

    @Serializable data object Library : Route

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

    /**
     * [route] opened again above itself (an artist, a similar one, the first again), for the
     * [visit]th time: an entry of its own, so the page starts at its top and the one below keeps
     * its place. Screens never see it ([page]).
     */
    @Serializable data class Again(val route: Route, val visit: Int) : Route

    /** The page this entry shows. */
    val page: Route get() = if (this is Again) route else this
}

/** The three tabs, in order, with labels always visible. */
enum class Tab(
    val root: Route,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
) {
    Home(Route.Home, R.string.tab_home, R.drawable.ic_home, R.drawable.ic_home_selected),
    Search(Route.Search, R.string.tab_search, R.drawable.ic_search, R.drawable.ic_search_selected),
    Library(Route.Library, PlaybackR.string.library, R.drawable.ic_library, R.drawable.ic_library_selected),
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
private fun Offers.offerOrTake(offer: Offer, haptics: HapticFeedback) {
    if (takes(offer)) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
    offer(offer)
}

/** [this] offered to play on the speaker in use, with [button]. */
private fun MediaUi.offer(@StringRes button: Int, take: () -> Unit) =
    Offer(about = key, title = title, button = button, art = art, kind = kind, secondTapTakes = true, take = take)

/** Whether [key] plays on the speaker in use right now, not only rests there. */
private fun Playing.plays(key: String): Boolean = nowPlaying.value?.let { it.key == key && it.isPlaying } == true
