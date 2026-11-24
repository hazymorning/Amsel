package io.github.hazymorning.amsel.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.hazymorning.amsel.AppGraph
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.components.EmptyState
import io.github.hazymorning.amsel.ui.components.LocalDownloadMarks
import io.github.hazymorning.amsel.ui.components.LocalMusicPlays
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.navigation.BarItem
import io.github.hazymorning.amsel.ui.navigation.DeepLink
import io.github.hazymorning.amsel.ui.navigation.DockControl
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.Route
import io.github.hazymorning.amsel.ui.screens.home.HomeRoute
import io.github.hazymorning.amsel.ui.screens.home.SeeAllRoute
import io.github.hazymorning.amsel.ui.screens.library.LibraryRoute
import io.github.hazymorning.amsel.ui.screens.pages.AlbumRoute
import io.github.hazymorning.amsel.ui.screens.pages.ArtistRoute
import io.github.hazymorning.amsel.ui.screens.pages.AudiobookRoute
import io.github.hazymorning.amsel.ui.screens.pages.PlaylistRoute
import io.github.hazymorning.amsel.ui.screens.pages.PodcastRoute
import io.github.hazymorning.amsel.ui.screens.pages.SimilarSongsRoute
import io.github.hazymorning.amsel.ui.screens.player.NowPlayingRoute
import io.github.hazymorning.amsel.ui.screens.search.SearchRoute
import io.github.hazymorning.amsel.ui.screens.settings.AppearanceRoute
import io.github.hazymorning.amsel.ui.screens.settings.BarRoute
import io.github.hazymorning.amsel.ui.screens.settings.DockRoute
import io.github.hazymorning.amsel.ui.screens.settings.FollowRoute
import io.github.hazymorning.amsel.ui.screens.settings.HintsRoute
import io.github.hazymorning.amsel.ui.screens.settings.LibraryKindsRoute
import io.github.hazymorning.amsel.ui.screens.settings.LicencesRoute
import io.github.hazymorning.amsel.ui.screens.settings.SettingsRoute
import io.github.hazymorning.amsel.ui.screens.settings.SleepTimerRoute
import io.github.hazymorning.amsel.ui.screens.setup.SetupRoute
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.ThemeMode
import io.github.hazymorning.amsel.ui.theme.applyNightMode
import io.github.hazymorning.amsel.ui.theme.isNight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class AppActions(
    private val context: Context,
    private val graph: AppGraph,
    private val sheet: MutableState<AppSheet?>,
    private val askNotifications: () -> Unit = {},
) {
    fun open(next: AppSheet) {
        sheet.value = next
    }

    /**
     * Null once the download may start (notifications asked for); without an Audiobookshelf account,
     * the sign-in sheet, which downloads [item] itself after signing in.
     */
    fun beforeDownload(item: MediaUi): AppSheet? {
        if (graph.downloading.account.value == null) return AppSheet.AbsSignIn(item)
        askNotifications()
        return null
    }

    fun download(item: MediaUi) {
        val first = beforeDownload(item)
        if (first != null) open(first) else graph.downloading.download(item.key)
    }

    fun more(target: ActionTarget) = open(AppSheet.Actions(target))

    fun playOn() = open(AppSheet.PlayOn)

    fun group() = open(AppSheet.Group)

    fun openWebUi() {
        graph.data.session.address.value?.let { openLink(it.toString()) }
    }

    fun openLink(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: ActivityNotFoundException) {
            graph.messages.show(R.string.error_no_browser)
        }
    }
}

val LocalAppActions = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }

/** [onOpened] runs once [opening] has been handled, and also when it cannot be (signed out). */
@Composable
fun AppRoot(graph: AppGraph, opening: DeepLink? = null, onOpened: () -> Unit = {}) {
    val prefs by graph.data.prefs.prefs.collectAsStateWithLifecycle()
    val stored = prefs?.theme
    val context = LocalContext.current
    // Only once the prefs are read: the System default before that is not the user's choice.
    LaunchedEffect(stored) { if (stored != null) context.applyNightMode(ThemeMode.of(stored)) }
    AmselTheme(ThemeMode.of(stored)) {
        SystemBarsFollowTheme()
        val session by graph.data.session.state.collectAsStateWithLifecycle()
        val signedOut = session == SessionState.NeedsSetup || session == SessionState.Unsupported
        LaunchedEffect(signedOut, opening) { if (signedOut && opening != null) onOpened() }
        when (session) {
            // MainActivity keeps the system splash on screen meanwhile.
            SessionState.Restoring -> Unit

            SessionState.NeedsSetup -> SetupRoute()

            SessionState.Unsupported -> EmptyState(
                R.drawable.ic_offline,
                stringResource(PlaybackR.string.error_too_old),
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                actionLabel = stringResource(R.string.retry),
                onAction = graph.data.session::reconnect,
            )

            // Waiting, Ready and Offline share one call, so the screens survive the connection coming and going.
            else -> Main(graph, opening, onOpened)
        }
    }
}

/** The bars' icons follow the app's theme, which may differ from the system's. */
@Composable
private fun SystemBarsFollowTheme() {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    val light = !MaterialTheme.colorScheme.isNight
    LaunchedEffect(window, view, light) {
        val bars = WindowCompat.getInsetsController(window, view)
        bars.isAppearanceLightStatusBars = light
        bars.isAppearanceLightNavigationBars = light
    }
}

@Composable
private fun Main(graph: AppGraph, opening: DeepLink?, onOpened: () -> Unit) {
    val context = LocalContext.current
    val nowPlaying by graph.playing.nowPlaying.collectAsStateWithLifecycle()
    val speaker by graph.playing.speaker.collectAsStateWithLifecycle()
    val offer by graph.offers.shown.collectAsStateWithLifecycle()
    // Survives a rotation but not the activity, so a later launch never reopens a sheet.
    val sheet = retain { mutableStateOf<AppSheet?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val askNotifications = rememberNotificationAsk()
    val actions = remember(graph, context, askNotifications) { AppActions(context, graph, sheet, askNotifications) }

    ShowMessages(graph.messages, snackbar)
    // These links close any open sheet; the speaker picker links open a sheet themselves.
    LaunchedEffect(opening) {
        if (opening == DeepLink.Search || opening == DeepLink.NowPlaying ||
            opening == DeepLink.UpNext
        ) {
            sheet.value = null
        }
    }
    // Android 17 blocks a local server until the user grants local network access, also on a phone
    // set up before the update (only Setup asks). While offline without it, ask once per launch.
    val reconnect = rememberRetry()
    var asked by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(graph, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                graph.data.session.state.map {
                    it is SessionState.Offline
                }.distinctUntilChanged().collectLatest { offline ->
                    if (!offline) {
                        graph.messages.withdraw(reconnect)
                        return@collectLatest
                    }
                    if (!asked && context.needsLocalNetwork()) {
                        asked = true
                        reconnect()
                    }
                    // A brief drop (a network change, the phone waking) reconnects within the delay and shows nothing.
                    delay(OFFLINE_NOTICE_MS)
                    graph.messages.show(PlaybackR.string.error_not_connected, retry = reconnect)
                }
            } finally {
                // Retry uses this activity's permission launcher, invalid once it stops; the next
                // start shows the message again with its own.
                graph.messages.withdraw(reconnect)
            }
        }
    }

    // The download service may be refused while the notification permission dialog covers the app;
    // pending downloads resume once the app is in front again.
    LaunchedEffect(graph, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { graph.downloading.resume() }
    }

    LaunchedEffect(graph, actions, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            graph.downloadOffer.offers.collect { item ->
                graph.messages.showDownload(R.string.download_offer, item.title) { actions.download(item) }
            }
        }
    }

    val downloads = graph.downloading.marks.collectAsStateWithLifecycle()
    val plays = remember { derivedStateOf { nowPlaying?.isPlaying == true } }
    val prefs by graph.data.prefs.prefs.collectAsStateWithLifecycle()
    val bar = remember(prefs?.barPlaces) { BarItem.of(prefs?.barPlaces) }
    val dock = remember(prefs?.dockControls) { DockControl.arrangement(prefs?.dockControls).visible }
    CompositionLocalProvider(
        LocalAppActions provides actions,
        LocalDownloadMarks provides downloads,
        LocalMusicPlays provides plays,
    ) {
        AppShell(
            nowPlaying = nowPlaying,
            speaker = speaker,
            onSpeaker = actions::playOn,
            onGroup = actions::group,
            onControl = graph.playback::control,
            snackbarHostState = snackbar,
            opening = opening,
            onOpened = onOpened,
            offer = offer,
            onTakeOffer = graph.offers::take,
            onDismissOffer = graph.offers::dismiss,
            bar = bar,
            dock = dock,
            onDockControl = { control ->
                when (control) {
                    DockControl.Favorite -> graph.playing.toggleHeart()
                    DockControl.Volume -> actions.open(AppSheet.Volume)
                    DockControl.Sleep -> actions.open(AppSheet.SleepTimer)
                    else -> actions.playOn()
                }
            },
            slots = ShellSlots(
                screen = { route, nav -> RouteContent(route, nav) },
                nowPlaying = { onClose, nav -> NowPlayingRoute(onClose, nav) },
                sheets = { nav -> AppSheets(sheet, nav) },
            ),
        )
    }
}

@Composable
private fun RouteContent(route: Route, navigator: Navigator) {
    when (route) {
        Route.Home -> HomeRoute(navigator)
        Route.Search -> SearchRoute(navigator)
        Route.Library -> LibraryRoute(navigator)
        is Route.Shelf -> LibraryRoute(navigator, only = route.kind)
        is Route.Album -> AlbumRoute(navigator, route.key)
        is Route.Artist -> ArtistRoute(navigator, route.key)
        is Route.Playlist -> PlaylistRoute(navigator, route.key)
        is Route.Audiobook -> AudiobookRoute(navigator, route.key)
        is Route.Podcast -> PodcastRoute(navigator, route.key)
        is Route.SimilarSongs -> SimilarSongsRoute(navigator, route.key, route.title)
        is Route.SeeAll -> SeeAllRoute(navigator, route.option, title = route.title)
        Route.Settings -> SettingsRoute(navigator)
        Route.SettingsPlace -> SettingsRoute(navigator, asPlace = true)
        Route.Bar -> BarRoute(navigator)
        Route.Dock -> DockRoute(navigator)
        Route.Hints -> HintsRoute(navigator)
        Route.Follow -> FollowRoute(navigator)
        Route.LibraryKinds -> LibraryKindsRoute(navigator)
        Route.HomeEdit -> HomeRoute(navigator, editOnly = true)
        Route.Appearance -> AppearanceRoute(navigator)
        Route.SleepTimer -> SleepTimerRoute(navigator)
        Route.Licences -> LicencesRoute(navigator)
        is Route.Again -> RouteContent(route.route, navigator)
    }
}

private const val OFFLINE_NOTICE_MS = 8_000L
