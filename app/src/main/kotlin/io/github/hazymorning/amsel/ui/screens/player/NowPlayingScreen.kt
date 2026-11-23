package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.AnswerUpNext
import io.github.hazymorning.amsel.ui.AppSheet
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.PlayerLayer
import io.github.hazymorning.amsel.ui.components.Artwork
import io.github.hazymorning.amsel.ui.components.BarTitleInset
import io.github.hazymorning.amsel.ui.components.IconAction
import io.github.hazymorning.amsel.ui.components.MoreButton
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.chapterLine
import io.github.hazymorning.amsel.ui.components.rememberSideSwipe
import io.github.hazymorning.amsel.ui.model.ActionsContext
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.PlayerControl
import io.github.hazymorning.amsel.ui.model.TrackUi
import io.github.hazymorning.amsel.ui.model.byline
import io.github.hazymorning.amsel.ui.model.sourceLine
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.Route
import io.github.hazymorning.amsel.ui.rememberPlayerLayerState
import io.github.hazymorning.amsel.ui.theme.CoverColors
import io.github.hazymorning.amsel.ui.theme.Elevation
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.extendForShadow
import io.github.hazymorning.amsel.ui.theme.isNight
import io.github.hazymorning.amsel.ui.theme.rememberCoverColors
import kotlinx.coroutines.launch

/**
 * [colors] overrides the cover's hue (tests); null takes it from the artwork. [onArtist] leads to
 * the song's artist, the book's author or the episode's podcast. A book or an episode names its
 * chapter under its title, and its chapters open from the footer's last button ([onChapters]), where
 * music has Up next: never on the page itself, where a stray touch went to another chapter.
 */
@Composable
fun NowPlayingScreen(
    state: NowPlayingUi,
    onControl: (PlayerControl) -> Unit,
    onClose: () -> Unit,
    onMore: () -> Unit,
    onArtist: () -> Unit,
    onSpeaker: () -> Unit,
    onUpNext: () -> Unit,
    modifier: Modifier = Modifier,
    colors: CoverColors? = null,
    onChapters: () -> Unit = onUpNext,
    /** Null leaves the heart out (a guest, who cannot change the library). */
    onLike: (() -> Unit)? = {},
    sleepLeft: String? = null,
    onSleep: () -> Unit = {},
    onBookmark: () -> Unit = {},
    onVolume: () -> Unit = {},
) {
    val extracted = rememberCoverColors(if (colors == null) state.art else null)
    val night = MaterialTheme.colorScheme.isNight
    val page = MaterialTheme.colorScheme.surface
    // The whole page leans a little to the cover. By day the cover stands on its shadow; at night,
    // where a shadow is lost, it stands on its own.
    val wash =
        animateColorAsState((colors ?: extracted)?.tint(page, night) ?: page, Motion.effectsSlow(), label = "wash")
    val position = rememberPlaybackPosition(state)

    // The foot's inset is the stage's to place the footer by: the gesture handle lies in the room under it.
    val foot = footRoom()
    Column(
        modifier
            .fillMaxSize()
            .drawBehind { drawRect(wash.value) }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        PageTopBar(
            title = state.sourceLine(),
            onBack = onClose,
            quiet = true,
            backIcon = R.drawable.ic_collapse,
            backLabel = stringResource(R.string.close),
            inset = NowPlayingInset,
            titleInset = if (sleepLeft != null) BarTitleInset + Sizes.TouchTarget else BarTitleInset,
            actions = {
                // A sleep timer that runs shows beside the menu it was set from, in moss, as what
                // runs is; what is left of it stands in its sheet.
                if (sleepLeft != null) {
                    IconAction(
                        R.drawable.ic_sleep,
                        stringResource(R.string.sleep_timer_running, sleepLeft),
                        onSleep,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
                // The menu waits for the song, so it is never a control that does nothing.
                if (state.key != null) MoreButton(state.title, onMore)
            },
        )
        // A sideways swipe on the cover is previous and next, as the dock's row has it.
        val swipe = rememberSideSwipe(state.key ?: state.title, enabled = state.canSkip) { forward ->
            onControl(if (forward) PlayerControl.Next else PlayerControl.Previous)
        }
        Stage(foot, Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            Artwork(
                state.art,
                // The cover's shade moves and fades with it.
                Modifier.then(swipe.detect).extendForShadow(Elevation.Cover, swipe.follow),
                kind = if (state.spoken) state.kind else MediaKind.Track,
                elevated = !night,
                name = state.album ?: state.title,
            )
            val chapter = rememberChapter(state, position)
            // A book or an episode with chapters is headed by the chapter it is in, the book under it.
            val heading = chapter.value?.let { chapterLine(it) }
            val line = if (heading != null) state.title else state.byline()
            val linked = chapter.value == null &&
                (if (state.kind == MediaKind.Episode) state.parentKey else state.artistKey) != null
            val linkLabel = stringResource(
                when (state.kind) {
                    MediaKind.Episode -> R.string.player_action_go_to_podcast
                    MediaKind.Audiobook -> R.string.player_action_go_to_author
                    else -> R.string.player_action_go_to_artist
                },
            )
            TrackInfo(
                heading ?: state.title,
                line,
                onArtist = if (linked) onArtist else null,
                linkLabel = linkLabel,
                liked = state.favorite,
                onLike = onLike?.takeIf { state.likeable },
            )
            SeekBar(state, position, chapter, onSeek = { onControl(PlayerControl.Seek(it)) })
            TransportRow(state, onControl)
            Footer(
                state,
                onSpeaker = onSpeaker,
                onBookmark = onBookmark,
                onVolume = onVolume,
                onUpNext = if (state.spoken) onChapters else onUpNext,
            )
        }
    }
}

/** Now Playing for the current speaker, with Up next as a layer above it that drags and previews Back alike. */
@Composable
fun NowPlayingRoute(onClose: () -> Unit, navigator: Navigator, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val actions = LocalAppActions.current
    val state by graph.playing.nowPlaying.collectAsStateWithLifecycle()
    val speaker by graph.playing.speaker.collectAsStateWithLifecycle()
    val timers by graph.sleepTimer.timers.collectAsStateWithLifecycle()
    val permissions by graph.data.permissions.collectAsStateWithLifecycle()
    val canWrite = permissions.canWriteLibrary
    val upNext = rememberPlayerLayerState()
    val scope = rememberCoroutineScope()
    val np = state ?: return
    val closeUpNext: () -> Unit = { scope.launch { upNext.close() } }
    AnswerUpNext { if (np.spoken) actions.open(AppSheet.Chapters) else upNext.open() }
    Box(modifier.fillMaxSize()) {
        NowPlayingScreen(
            state = np,
            onControl = graph.playback::control,
            onClose = onClose,
            onMore = { np.moreTarget()?.let(actions::more) },
            onArtist = {
                val parent = np.parentKey
                when {
                    np.kind == MediaKind.Episode && parent != null -> navigator.open(Route.Podcast(parent))
                    else -> np.artistKey?.let { navigator.open(Route.Artist(it)) }
                }
            },
            onSpeaker = actions::playOn,
            onUpNext = { scope.launch { upNext.open() } },
            onChapters = { actions.open(AppSheet.Chapters) },
            onLike = if (canWrite) {
                {
                    np.key?.let { key ->
                        graph.favorites.set(key, !np.favorite)
                        val song = MediaUi(key, np.kind, np.title, np.artist, np.art)
                        if (np.favorite) {
                            graph.hints.unliked(key, np.title)
                        } else {
                            graph.hints.liked(song) { actions.open(AppSheet.Playlists(ActionTarget.Media(song))) }
                        }
                    }
                }
            } else {
                null
            },
            sleepLeft = sleepLeftText(timers[np.speaker.id]),
            onSleep = { actions.open(AppSheet.SleepTimer) },
            onBookmark = graph.bookmarks::addHere,
            onVolume = { actions.open(AppSheet.Volume) },
        )
        PlayerLayer(upNext, onClose = closeUpNext) {
            UpNextRoute(speaker.id, onClose = closeUpNext, layer = upNext)
        }
    }
}

/**
 * What Now Playing's ⋮ opens: the song, the station, or the book or episode, which lead to their
 * page; the DJ's words open it as a song would, for the speaker's rows. Null while the server has
 * not named it.
 */
fun NowPlayingUi.moreTarget(): ActionTarget? {
    val key = key ?: return null
    return if (kind == MediaKind.Radio) {
        ActionTarget.Media(
            MediaUi(key, MediaKind.Radio, title, art = art, favorite = favorite),
            context = ActionsContext.NowPlaying,
        )
    } else if (spoken) {
        val item = MediaUi(
            key,
            kind,
            title,
            artist.ifBlank { null },
            art,
            favorite,
            resumeMs = positionMs,
            parentKey = parentKey,
            fullyPlayed = fullyPlayed,
            artistKey = artistKey,
        )
        ActionTarget.Media(item, context = ActionsContext.NowPlaying)
    } else {
        ActionTarget.Track(
            TrackUi(
                key,
                title,
                artist,
                album,
                art,
                favorite = favorite,
                albumKey = albumKey,
                artistKey = artistKey,
                djBreak = djBreak,
            ),
            context = ActionsContext.NowPlaying,
        )
    }
}
