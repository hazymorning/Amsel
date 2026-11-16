package io.github.hazymorning.amsel.ui.screens.settings

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
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.Route
import io.github.hazymorning.amsel.ui.screens.downloads.AbsSignIn
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import io.github.hazymorning.amsel.ui.theme.Space

/**
 * Settings in three blocks, as the platforms' own settings set them, each a rounded block of rows
 * under its name: the account; playback, from which speaker Amsel opens on to how the sleep timer
 * ends; and the app. What is set once and seldom looked at again, the sleep timer's ways and how
 * the app looks and what its pages and bars show, is a page of its own, so the first page stays
 * short. Amsel's line, the licence and the blackbird close the page, quietly ([PageWithBird]). The
 * version stands under Updates, whose sheet looks for a newer one.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenWebUi: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    /** The speakers Follow the music may pick, named under it, and its page. */
    followSpeakers: List<FollowSpeaker> = emptyList(),
    onFollow: () -> Unit = {},
    onVolumeLimitChange: (Int) -> Unit = {},
    /** The sleep timer's page: how it ends and when it starts by itself. */
    onSleepTimer: () -> Unit = {},
    /** The kinds of suggestion turned off, and their page. */
    hintsOff: Set<String> = emptySet(),
    onHints: () -> Unit = {},
    /** The appearance page: the theme, the language, and what Home, the Library and the bars show. */
    onAppearance: () -> Unit = {},
    update: UpdateState = UpdateState.Unknown,
    onLookForUpdatesChange: (Boolean) -> Unit = {},
    onLookForUpdate: () -> Unit = {},
    onInstallUpdate: (UpdateState.Available) -> Unit = {},
    downloads: DownloadSettings = DownloadSettings(),
    onDownloadOnWifiChange: (Boolean) -> Unit = {},
    onAbsSignIn: AbsSignIn = { _, _, _ -> null },
    onAbsSignOut: () -> Unit = {},
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
            // Where the music goes, how loud it may get, when it stops, and what Amsel suggests on the way.
            SettingsGroup(stringResource(R.string.settings_playback), above = BetweenGroups) {
                row { shape ->
                    SettingRow(
                        stringResource(R.string.settings_follow),
                        shape,
                        subtitle = followLine(state.followPlaying, followSpeakers, state.followSkipped),
                        onClick = onFollow,
                    )
                }
                row { shape ->
                    ValueRow(stringResource(R.string.settings_volume_limit), limitLabel(state.volumeLimit), shape) {
                        choosing = Choice.VolumeLimit
                    }
                }
                row { shape ->
                    SettingRow(
                        stringResource(R.string.settings_sleep),
                        shape,
                        subtitle = stringResource(R.string.settings_sleep_detail),
                        onClick = onSleepTimer,
                    )
                }
                row { shape ->
                    val on = hintsOn(hintsOff)
                    val all = HintKind.entries.size
                    val hints = when (on) {
                        all -> stringResource(R.string.hints_on_all)
                        0 -> stringResource(R.string.hints_on_none)
                        else -> stringResource(R.string.hints_on_some, on, all)
                    }
                    SettingRow(stringResource(R.string.hints), shape, subtitle = hints, onClick = onHints)
                }
            }
            SettingsGroup(stringResource(R.string.settings_app), above = BetweenGroups) {
                row { shape ->
                    SettingRow(
                        stringResource(R.string.settings_appearance),
                        shape,
                        subtitle = stringResource(R.string.settings_appearance_detail),
                        onClick = onAppearance,
                    )
                }
                row { shape ->
                    SettingRow(
                        stringResource(R.string.downloads),
                        shape,
                        subtitle = downloadsLine(downloads.account),
                        onClick = { choosing = Choice.Downloads },
                    )
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
            }
        }
    }
}

/** From one block's foot to the next group's name. */
internal val BetweenGroups = Space.S8

private enum class Choice { VolumeLimit, Updates, Downloads }

@Composable
private fun limitLabel(level: Int): String =
    if (level == 0) stringResource(R.string.sleep_off) else stringResource(R.string.player_volume_percent, level)

/** No limit, or one of these: loud enough for a room, short of what startles. */
private val LIMITS = listOf(0, 40, 50, 60, 70, 80)

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

@Composable
fun SettingsRoute(navigator: Navigator, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val viewModel = rememberSettingsViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val update by graph.updates.state.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val account by graph.downloading.account.collectAsStateWithLifecycle()
    val entries by graph.downloading.entries.collectAsStateWithLifecycle()
    val prefs by graph.data.prefs.prefs.collectAsStateWithLifecycle()
    val used by produceState<Long?>(null, entries) { value = graph.data.downloads.used() }
    SettingsScreen(
        state = state,
        onBack = { navigator.back() },
        onOpenWebUi = { actions.openWebUi() },
        onSignOut = viewModel::onSignOut,
        followSpeakers = rememberFollowSpeakers(),
        onFollow = { navigator.open(Route.Follow) },
        onVolumeLimitChange = viewModel::onVolumeLimitChange,
        onSleepTimer = { navigator.open(Route.SleepTimer) },
        hintsOff = prefs?.hintsOff.orEmpty(),
        onHints = { navigator.open(Route.Hints) },
        onAppearance = { navigator.open(Route.Appearance) },
        update = update,
        onLookForUpdatesChange = viewModel::onLookForUpdatesChange,
        onLookForUpdate = graph.updates::lookNow,
        onInstallUpdate = graph.updates::install,
        downloads = DownloadSettings(account, used, last = prefs?.lastAbsAccount()),
        onDownloadOnWifiChange = viewModel::onDownloadOnWifiChange,
        onAbsSignIn = graph.downloading::signIn,
        onAbsSignOut = graph.downloading::signOut,
        modifier = modifier,
    )
}
