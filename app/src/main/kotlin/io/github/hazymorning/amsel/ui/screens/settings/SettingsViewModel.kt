package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.BuildConfig
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.MaUser
import io.github.hazymorning.amsel.api.ServerAddress
import io.github.hazymorning.amsel.app.MessageController
import io.github.hazymorning.amsel.app.retryWhileOpen
import io.github.hazymorning.amsel.data.Prefs
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.model.ServerStatus
import io.github.hazymorning.amsel.ui.screens.setup.hostOf
import io.github.hazymorning.amsel.ui.theme.ThemeMode
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings: who is signed in where, the theme, and Sign out. The theme answers the tap at once;
 * Sign out runs in [appScope] so forgetting the server's data finishes after this screen is gone
 * (the app root shows Setup as soon as the session says NeedsSetup).
 */
class SettingsViewModel(
    session: StateFlow<SessionState>,
    address: StateFlow<ServerAddress?>,
    /** Who is signed in, kept by the session while offline. */
    user: StateFlow<MaUser?>,
    prefs: StateFlow<Prefs?>,
    private val saveTheme: suspend (String) -> Unit,
    private val saveFade: suspend (Int) -> Unit = {},
    private val saveShake: suspend (Boolean) -> Unit = {},
    private val saveSleepOnPlay: suspend (Boolean) -> Unit = {},
    private val saveVolumeLimit: suspend (Int) -> Unit = {},
    private val saveLookForUpdates: suspend (Boolean) -> Unit = {},
    private val saveDownloadOnWifi: suspend (Boolean) -> Unit = {},
    /** Signs out and forgets the server's data (Session.signOut, then DataGraph.clear). */
    private val signOutAndForget: suspend () -> Unit,
    private val messages: MessageController,
    private val appScope: CoroutineScope,
    private val appVersion: String,
) : ViewModel() {
    /** The theme just tapped, shown until the stored preference catches up (it then agrees). */
    private val chosenTheme = MutableStateFlow<ThemeMode?>(null)
    private var signOut: Job? = null

    val state: StateFlow<SettingsUiState> =
        combine(prefs, session, address, user, chosenTheme) { p, s, a, u, theme ->
            settingsState(p, s, a, u, theme, appVersion)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            settingsState(prefs.value, session.value, address.value, user.value, null, appVersion),
        )

    fun onThemeChange(mode: ThemeMode) {
        chosenTheme.value = mode
        // Main.immediate: saves start in tap order, and DataStore applies them in that order.
        save(again = { onThemeChange(mode) }, undo = { chosenTheme.compareAndSet(mode, null) }) { saveTheme(mode.pref) }
    }

    fun onFadeChange(seconds: Int): Unit = save(again = { onFadeChange(seconds) }) { saveFade(seconds) }

    fun onShakeChange(on: Boolean): Unit = save(again = { onShakeChange(on) }) { saveShake(on) }

    fun onSleepOnPlayChange(on: Boolean): Unit = save(again = { onSleepOnPlayChange(on) }) { saveSleepOnPlay(on) }

    fun onVolumeLimitChange(level: Int): Unit = save(again = { onVolumeLimitChange(level) }) { saveVolumeLimit(level) }

    fun onCheckForUpdatesChange(on: Boolean): Unit = save(again = {
        onCheckForUpdatesChange(on)
    }) { saveLookForUpdates(on) }

    fun onDownloadOnWifiChange(on: Boolean): Unit =
        save(again = { onDownloadOnWifiChange(on) }) { saveDownloadOnWifi(on) }

    /**
     * Stores one setting. A failure first runs [undo] (a theme goes back to the stored one, unless a
     * later tap is already showing), then says so, with Retry while Settings is open.
     */
    private fun save(again: () -> Unit, undo: () -> Unit = {}, write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                write()
            } catch (e: IOException) {
                undo()
                messages.show(R.string.settings_save_failed, retry = retryWhileOpen(messages, again))
            }
        }
    }

    fun onSignOut() {
        if (signOut?.isActive == true) return
        signOut = appScope.launch {
            try {
                signOutAndForget()
            } catch (e: IOException) {
                messages.show(R.string.settings_sign_out_failed, retry = ::onSignOut)
            }
        }
    }
}

/** The server's own name as the session knows it; "" when the server has none. */
internal fun serverNameOf(session: SessionState): String = when (session) {
    is SessionState.Ready -> session.server.name
    is SessionState.Waiting -> session.serverName
    is SessionState.Offline -> session.serverName
    else -> null
}.orEmpty()

/**
 * Settings from the session, its [address] and [user], the stored theme ([prefs] is null until
 * read) and a theme just tapped. The address reads as Setup showed it: the host alone
 * (`192.168.1.20`, `music.example.com`), never a scheme or port.
 */
internal fun settingsState(
    prefs: Prefs?,
    session: SessionState,
    address: ServerAddress?,
    user: MaUser?,
    chosenTheme: ThemeMode?,
    appVersion: String,
): SettingsUiState {
    val stored = prefs ?: Prefs()
    return SettingsUiState(
        userName = user?.shownName.orEmpty(),
        serverName = serverNameOf(session),
        serverAddress = address?.let(::hostOf).orEmpty(),
        connection = when (session) {
            is SessionState.Ready -> ServerStatus.Connected
            is SessionState.Offline -> ServerStatus.Offline
            else -> ServerStatus.Connecting
        },
        theme = chosenTheme ?: ThemeMode.of(prefs?.theme),
        appVersion = appVersion,
        sleepFadeSec = stored.sleepFadeSec,
        sleepShake = stored.sleepShake,
        sleepOnPlay = stored.sleepOnPlay,
        followPlaying = stored.followPlaying,
        followSkipped = stored.followSkipped,
        volumeLimit = stored.volumeLimit,
        lookForUpdates = stored.lookForUpdates,
        downloadOnWifi = stored.downloadOnWifi,
    )
}

/** Settings fit one phone screen: account, theme, Open Music Assistant, the app. All of it is local. */
@Immutable
data class SettingsUiState(
    /** The Music Assistant user, as Music Assistant names them; blank when not known yet. */
    val userName: String,
    /** The server's name in Music Assistant; blank when it has none. */
    val serverName: String,
    /** The server's host, as Setup showed it. */
    val serverAddress: String,
    val theme: ThemeMode = ThemeMode.System,
    /** The app's version name, such as "0.1.0". */
    val appVersion: String,
    val sleepFadeSec: Int = 30,
    val sleepShake: Boolean = true,
    val sleepOnPlay: Boolean = false,
    val followPlaying: Boolean = false,
    /** The speakers following the music leaves out. */
    val followSkipped: Set<String> = emptySet(),
    /** Above this level a speaker plays louder only after a question; 0 for no limit. */
    val volumeLimit: Int = 0,
    val lookForUpdates: Boolean = false,
    /** Downloads wait for Wi-Fi rather than use mobile data. */
    val downloadOnWifi: Boolean = true,
    /** Whether the server answers now, said after where it is. */
    val connection: ServerStatus = ServerStatus.Connected,
)

/** The settings wired to the session and the stored preferences, for Settings and its pages. */
@Composable
internal fun rememberSettingsViewModel(): SettingsViewModel {
    val graph = LocalContext.current.graph
    return viewModel {
        val data = graph.data
        SettingsViewModel(
            session = data.session.state,
            address = data.session.address,
            user = data.session.user,
            prefs = data.prefs.prefs,
            saveTheme = data.prefs::setTheme,
            saveFade = data.prefs::setSleepFade,
            saveShake = data.prefs::setSleepShake,
            saveSleepOnPlay = data.prefs::setSleepOnPlay,
            saveVolumeLimit = data.prefs::setVolumeLimit,
            saveLookForUpdates = data.prefs::setLookForUpdates,
            saveDownloadOnWifi = data.prefs::setDownloadOnWifi,
            // The data graph forgets the server's data once the session has ended.
            signOutAndForget = data.session::signOut,
            messages = graph.messages,
            appScope = graph.scope,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }
}
