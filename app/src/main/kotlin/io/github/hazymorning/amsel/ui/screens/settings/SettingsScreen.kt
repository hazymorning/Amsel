package io.github.hazymorning.amsel.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.BuildConfig
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.UpdateState
import io.github.hazymorning.amsel.data.lastAbsAccount
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.navigation.BarPlace
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.Route
import io.github.hazymorning.amsel.ui.screens.downloads.AbsSignIn
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.ThemeMode

/**
 * Settings in three blocks, as the platforms' own settings set them: the account, the sleep timer and
 * the app, each a rounded block of rows under its name; Amsel's line, the licence and the blackbird close the page,
 * quietly ([PageWithBird]). The version stands under Updates, whose sheet looks for a newer one.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenWebUi: () -> Unit,
    onSignOut: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    /** Opens Android's language page for the app; null where Android has none (before 13). */
    onLanguage: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onFadeChange: (Int) -> Unit = {},
    onShakeChange: (Boolean) -> Unit = {},
    onSleepOnPlayChange: (Boolean) -> Unit = {},
    onFollowChange: (Boolean) -> Unit = {},
    onVolumeLimitChange: (Int) -> Unit = {},
    update: UpdateState = UpdateState.Unknown,
    onLookForUpdatesChange: (Boolean) -> Unit = {},
    onLookForUpdate: () -> Unit = {},
    onInstallUpdate: (UpdateState.Available) -> Unit = {},
    downloads: DownloadSettings = DownloadSettings(),
    onDownloadOnWifiChange: (Boolean) -> Unit = {},
    onAbsSignIn: AbsSignIn = { _, _, _ -> null },
    onAbsSignOut: () -> Unit = {},
    /** The places the bottom bar shows, in its order, and the page that arranges them. */
    bar: List<BarPlace> = BarPlace.Default,
    onBar: () -> Unit = {},
) {
    val title = stringResource(R.string.settings)
    val scroll = rememberScrollState()
    var choosing by rememberSaveable { mutableStateOf<Choice?>(null) }
    // The title stays in the bar, as on every Android settings screen, so the first setting sits high.
    MaterialScreen(modifier, bar = { PageTopBar(title, onBack) }) { insets ->
        ScrollEdges(scroll)
        PageWithBird(Modifier.fillMaxSize().verticalScroll(scroll).padding(insets)) {
            // Blank until Music Assistant has said who is signed in (Settings opened while offline).
            val signedIn = if (state.userName.isBlank()) {
                stringResource(R.string.settings_signed_in)
            } else {
                stringResource(R.string.settings_signed_in_as, state.userName)
            }
            val signOut = stringResource(R.string.settings_sign_out)
            val signOutBeside = buttonFitsBeside(signedIn, signOut)
            SettingsGroup(stringResource(R.string.settings_account), above = Space.Sibling) {
                accountRows(signedIn, listOf(state.serverName, state.serverAddress), signOut, signOutBeside, onSignOut)
                row { shape ->
                    SettingRow(
                        stringResource(R.string.open_music_assistant),
                        shape,
                        subtitle = stringResource(R.string.settings_web_ui_detail),
                        onClick = onOpenWebUi,
                        trailing = { ExternalGlyph() },
                    )
                }
            }
            SettingsGroup(stringResource(R.string.settings_sleep), above = BetweenGroups) {
                row { shape ->
                    ValueRow(stringResource(R.string.settings_sleep_fade), fadeLabel(state.sleepFadeSec), shape) {
                        choosing = Choice.Fade
                    }
                }
                row { shape ->
                    ToggleRow(
                        stringResource(R.string.settings_sleep_shake),
                        stringResource(R.string.settings_sleep_shake_detail),
                        state.sleepShake,
                        shape,
                        onShakeChange,
                    )
                }
                row { shape ->
                    ToggleRow(
                        stringResource(R.string.settings_sleep_on_play),
                        stringResource(R.string.settings_sleep_on_play_detail),
                        state.sleepOnPlay,
                        shape,
                        onSleepOnPlayChange,
                    )
                }
            }
            SettingsGroup(stringResource(R.string.settings_app), above = BetweenGroups) {
                row { shape ->
                    ValueRow(stringResource(R.string.settings_theme), stringResource(themeLabel(state.theme)), shape) {
                        choosing = Choice.Theme
                    }
                }
                row { shape ->
                    // What the bar shows, in its order: the page arranges it, as a long press on the bar does.
                    val names = bar.map { stringResource(it.label) }.joinToString(", ")
                    SettingRow(stringResource(R.string.settings_bar), shape, subtitle = names, onClick = onBar)
                }
                row { shape ->
                    ToggleRow(
                        stringResource(R.string.settings_follow),
                        stringResource(R.string.settings_follow_detail),
                        state.followPlaying,
                        shape,
                        onFollowChange,
                    )
                }
                row { shape ->
                    ValueRow(stringResource(R.string.settings_volume_limit), limitLabel(state.volumeLimit), shape) {
                        choosing = Choice.VolumeLimit
                    }
                }
                row { shape ->
                    SettingRow(
                        stringResource(R.string.downloads),
                        shape,
                        subtitle = downloadsLine(downloads.account),
                        onClick = { choosing = Choice.Downloads },
                    )
                }
                if (onLanguage != null) {
                    row { shape ->
                        SettingRow(
                            stringResource(R.string.settings_language),
                            shape,
                            subtitle = stringResource(R.string.settings_language_current),
                            onClick = onLanguage,
                            trailing = { ExternalGlyph() },
                        )
                    }
                }
                row { shape ->
                    SettingRow(
                        stringResource(R.string.settings_updates),
                        shape,
                        subtitle = when (update) {
                            is UpdateState.Available -> stringResource(R.string.update_out, update.version)
                            is UpdateState.Loading -> stringResource(R.string.update_downloading, update.update.version)
                            else -> stringResource(R.string.settings_version, state.appVersion)
                        },
                        onClick = { choosing = Choice.Updates },
                    )
                }
            }
            LicenceNote()
        }
    }
    choosing?.let { choice ->
        // A choice applies at once, under the sheet as it slides away.
        AmselSheet(onDismiss = { choosing = null }) {
            when (choice) {
                Choice.Theme -> ChoiceSheet(
                    stringResource(R.string.settings_theme),
                    ThemeMode.entries,
                    state.theme,
                    label = { stringResource(themeLabel(it)) },
                    onPick = {
                        onThemeChange(it)
                        close()
                    },
                )

                Choice.Updates -> UpdatesSheetContent(
                    update,
                    state.appVersion,
                    lookAtStart = state.lookForUpdates,
                    onLookAtStart = onLookForUpdatesChange,
                    onLook = onLookForUpdate,
                    onInstall = onInstallUpdate,
                )

                Choice.Downloads -> DownloadsSheetContent(
                    downloads.copy(wifiOnly = state.downloadOnWifi),
                    onDownloadOnWifiChange,
                    onAbsSignIn,
                    onAbsSignOut,
                )

                Choice.VolumeLimit -> ChoiceSheet(
                    stringResource(R.string.settings_volume_limit),
                    LIMITS,
                    state.volumeLimit,
                    label = { limitLabel(it) },
                    detail = stringResource(R.string.settings_volume_limit_detail),
                    onPick = {
                        onVolumeLimitChange(it)
                        close()
                    },
                )

                Choice.Fade -> ChoiceSheet(
                    stringResource(R.string.settings_sleep_fade),
                    FADES,
                    state.sleepFadeSec,
                    label = { fadeLabel(it) },
                    onPick = {
                        onFadeChange(it)
                        close()
                    },
                )
            }
        }
    }
}

/** From one block's foot to the next group's name. */
private val BetweenGroups = Space.S8

private enum class Choice { Theme, Fade, VolumeLimit, Updates, Downloads }

@Composable
private fun limitLabel(level: Int): String =
    if (level == 0) stringResource(R.string.sleep_off) else stringResource(R.string.player_volume_percent, level)

/** No limit, or one of these: loud enough for a room, short of what startles. */
private val LIMITS = listOf(0, 40, 50, 60, 70, 80)

@Composable
private fun fadeLabel(seconds: Int): String =
    if (seconds == 0) stringResource(R.string.sleep_off) else stringResource(R.string.settings_sleep_seconds, seconds)

private val FADES = listOf(0, 10, 15, 30, 60)

@StringRes
private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.System -> R.string.settings_theme_system
    ThemeMode.Light -> R.string.settings_theme_light
    ThemeMode.Dark -> R.string.settings_theme_dark
}

/** Settings wired to the session and the stored preferences. */
@Composable
fun SettingsRoute(navigator: Navigator, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val viewModel = viewModel {
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
            saveFollow = data.prefs::setFollowPlaying,
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
    val state by viewModel.state.collectAsStateWithLifecycle()
    val update by graph.updates.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val context = LocalContext.current
    val account by graph.downloading.account.collectAsStateWithLifecycle()
    val entries by graph.downloading.entries.collectAsStateWithLifecycle()
    val prefs by graph.data.prefs.prefs.collectAsStateWithLifecycle()
    val used by produceState<Long?>(null, entries) { value = graph.data.downloads.used() }
    SettingsScreen(
        state = state,
        onBack = { navigator.back() },
        onOpenWebUi = { actions.openWebUi() },
        onSignOut = viewModel::onSignOut,
        onThemeChange = viewModel::onThemeChange,
        onFadeChange = viewModel::onFadeChange,
        onShakeChange = viewModel::onShakeChange,
        onSleepOnPlayChange = viewModel::onSleepOnPlayChange,
        onFollowChange = viewModel::onFollowChange,
        onVolumeLimitChange = viewModel::onVolumeLimitChange,
        update = update,
        onLookForUpdatesChange = viewModel::onLookForUpdatesChange,
        onLookForUpdate = graph.updates::lookNow,
        onInstallUpdate = graph.updates::install,
        downloads = DownloadSettings(account, used, last = prefs?.lastAbsAccount()),
        onDownloadOnWifiChange = viewModel::onDownloadOnWifiChange,
        onAbsSignIn = graph.downloading::signIn,
        onAbsSignOut = graph.downloading::signOut,
        bar = BarPlace.of(prefs?.barPlaces),
        onBar = { navigator.open(Route.Bar) },
        onLanguage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            { context.openLanguageSettings() }
        } else {
            null
        },
        modifier = modifier,
    )
}

/**
 * Android's own page for the app's language, which offers the languages in `locales_config.xml`;
 * the app's details page where a device leaves it out, so the tap always answers.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Context.openLanguageSettings() {
    val app = Uri.fromParts("package", packageName, null)
    try {
        startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, app))
    } catch (e: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))
    }
}
