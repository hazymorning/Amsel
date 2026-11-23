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
import io.github.hazymorning.amsel.ui.navigation.BarPlace
import io.github.hazymorning.amsel.ui.navigation.DockControl
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.Opening
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

/** What screens may ask of the app shell, wherever they are. Provided by [AppRoot]. */
class AppActions(
    private val context: Context,
    private val graph: AppGraph,
    private val sheet: MutableState<AppSheet?>,
    /** Asks for notifications, which show a download's progress, where Android needs asking. */
    private val askNotifications: () -> Unit = {},
) {
    fun open(next: AppSheet) {
        sheet.value = next
    }

    /**
     * What comes before downloading [item]: the first time, the sheet that signs in to
     * Audiobookshelf, which then downloads it; otherwise null, once notifications were asked for.
     */
    fun beforeDownload(item: MediaUi): AppSheet? {
        if (graph.downloading.account.value == null) return AppSheet.AbsSignIn(item)
        askNotifications()
        return null
    }

    /** Downloads [item] from its page. */
    fun download(item: MediaUi) {
        val first = beforeDownload(item)
        if (first != null) open(first) else graph.downloading.download(item.key)
    }

    /** Opens the ⋮ sheet (also the long-press shortcut). */
    fun more(target: ActionTarget) = open(AppSheet.Actions(target))

    fun playOn() = open(AppSheet.PlayOn)

    fun group() = open(AppSheet.Group)

    /** Opens Music Assistant's web UI in the browser. */
    fun openWebUi() {
        graph.data.session.address.value?.let { openLink(it.toString()) }
    }

    /** Opens [url] in the browser. */
    fun openLink(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: ActivityNotFoundException) {
            graph.messages.show(R.string.error_no_browser)
        }
    }
}

val LocalAppActions = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }

/**
 * The whole app: Setup until signed in, then the tabs, mini player, Now Playing and sheets.
 * [opening] is where a shortcut or a link asked to go; [onOpened] once the app is there, or
 * cannot go (no one signed in).
 */
@Composable
fun AppRoot(graph: AppGraph, opening: Opening? = null, onOpened: () -> Unit = {}) {
    val prefs by graph.data.prefs.prefs.collectAsStateWithLifecycle()
    val stored = prefs?.theme
    val context = LocalContext.current
    // Only once read: the System that stands in before then is not the person's choice.
    LaunchedEffect(stored) { if (stored != null) context.applyNightMode(ThemeMode.of(stored)) }
    AmselTheme(ThemeMode.of(stored)) {
        SystemBarsFollowTheme()
        val session by graph.data.session.state.collectAsStateWithLifecycle()
        val signedOut = session == SessionState.NeedsSetup || session == SessionState.Unsupported
        LaunchedEffect(signedOut, opening) { if (signedOut && opening != null) onOpened() }
        when (session) {
            // The system splash stays on screen meanwhile (MainActivity).
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

            // Waiting, Ready and Offline share this one call: the screens stay while the connection comes and goes.
            else -> Main(graph, opening, onOpened)
        }
    }
}

/** The status and navigation bars' glyphs follow the app's own theme, which may differ from the system's. */
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
private fun Main(graph: AppGraph, opening: Opening?, onOpened: () -> Unit) {
    val context = LocalContext.current
    val nowPlaying by graph.playing.nowPlaying.collectAsStateWithLifecycle()
    val speaker by graph.playing.speaker.collectAsStateWithLifecycle()
    val offer by graph.offers.shown.collectAsStateWithLifecycle()
    // Kept through a rotation, not past the activity: a later launch never reopens it.
    val sheet = retain { mutableStateOf<AppSheet?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val askNotifications = rememberNotificationAsk()
    val actions = remember(graph, context, askNotifications) { AppActions(context, graph, sheet, askNotifications) }

    ShowMessages(graph.messages, snackbar)
    // Search, Now Playing and Up next come up in front of whatever sheet was open; the speaker picker is one.
    LaunchedEffect(opening) {
        if (opening == Opening.Search || opening == Opening.NowPlaying || opening == Opening.UpNext) sheet.value = null
    }
    // On Android 17 a server on the local network is out of reach until the person allows it,
    // also for a phone set up before the update (Setup asked only then). Unreachable with the
    // permission missing: ask, once per launch; Retry asks again, or reconnects at once.
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
                        // Back again: a message about it would only be old news.
                        graph.messages.withdraw(reconnect)
                        return@collectLatest
                    }
                    if (!asked && context.needsLocalNetwork()) {
                        asked = true
                        reconnect()
                    }
                    // A socket that drops and comes back (the network changing, the phone waking) says nothing.
                    delay(OFFLINE_NOTICE_MS)
                    graph.messages.show(PlaybackR.string.error_not_connected, retry = reconnect)
                }
            } finally {
                // Its Retry asks through this activity's launcher, gone once it stops; the next
                // start says it again with its own.
                graph.messages.withdraw(reconnect)
            }
        }
    }

    // Started behind a dialog (Android's own, asking for notifications), the download service may
    // be refused: what waits goes on as soon as the app is in front again.
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
    val bar = remember(prefs?.barPlaces) { BarPlace.of(prefs?.barPlaces) }
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

/** How long Music Assistant stays out of reach before the app says so. */
private const val OFFLINE_NOTICE_MS = 8_000L
