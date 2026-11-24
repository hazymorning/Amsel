package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.UpdateState
import io.github.hazymorning.amsel.data.lastAbsAccount
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.components.SheetQuestion
import io.github.hazymorning.amsel.ui.components.TabHeader
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.Route
import io.github.hazymorning.amsel.ui.screens.downloads.AbsSignInAction
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import io.github.hazymorning.amsel.ui.theme.Space

/**
 * Settings in three blocks, as the platforms' own settings set them, each a rounded block of rows
 * under its name, each row led by its glyph and saying what it holds now: the account and whether
 * the server answers; playback, from which speaker Amsel opens on to how the sleep timer ends; and
 * the rest. What is set once and seldom looked at again, the sleep timer's ways and how the app
 * looks and what its pages and bars show, is a page of its own, so the first page stays short.
 * Signing out asks first. Amsel's line, the licence and the blackbird close the page, quietly
 * ([PageWithBird]). The version stands under Updates, a moss dot before it while a newer one waits.
 * As a place of its own in the bar ([onBack] null) the page has its big title, as every place has.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: (() -> Unit)?,
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
    onCheckForUpdatesChange: (Boolean) -> Unit = {},
    onCheckForUpdate: () -> Unit = {},
    onInstallUpdate: (UpdateState.Available) -> Unit = {},
    downloads: DownloadSettings = DownloadSettings(),
    onDownloadOnWifiChange: (Boolean) -> Unit = {},
    onAbsSignIn: AbsSignInAction = { _, _, _ -> null },
    onAbsSignOut: () -> Unit = {},
    /** The page of Amsel's licence, the privacy notice and the licences of software by others. */
    onLicences: () -> Unit = {},
) {
    val title = stringResource(R.string.settings)
    val scroll = rememberScrollState()
    var choosing by rememberSaveable { mutableStateOf<SettingsSheet?>(null) }
    val place = onBack == null
    val titleHeight = remember { mutableIntStateOf(0) }
    val scrolled by remember { derivedStateOf { titleHeight.intValue > 0 && scroll.value >= titleHeight.intValue } }
    // Opened from Home, the title stays in the bar with the way back, as on every Android settings
    // screen; as a place, its big title scrolls away and the slim bar names it then.
    MaterialScreen(
        modifier,
        bar = { PageTopBar(title, onBack, showTitle = !place || scrolled) },
        barShown = if (place) scrolled else null,
    ) { insets ->
        ScrollEdges(scroll)
        PageWithBird(Modifier.fillMaxSize().verticalScroll(scroll).padding(insets)) {
            if (place) TabHeader(title, Modifier.onSizeChanged { titleHeight.intValue = it.height }, below = 0.dp)
            // Blank until Music Assistant has said who is signed in (Settings opened while offline).
            val signedIn = if (state.userName.isBlank()) {
                stringResource(R.string.settings_signed_in)
            } else {
                stringResource(R.string.settings_signed_in_as, state.userName)
            }
            val signOut = stringResource(R.string.settings_sign_out)
            val signOutBeside = buttonFitsBeside(signedIn, signOut, glyph = true)
            val where = listOf(state.serverName, state.serverAddress, connectionLine(state.connection))
            SettingsGroup(
                stringResource(R.string.settings_account),
                above = if (place) TitleToGroup else Space.Sibling,
            ) {
                accountRows(signedIn, where, signOut, signOutBeside) { choosing = SettingsSheet.SignOut }
                row { shape ->
                    SettingRow(
                        stringResource(R.string.open_music_assistant),
                        shape,
                        subtitle = stringResource(R.string.settings_web_ui_detail),
                        onClick = onOpenWebUi,
                        glyph = R.drawable.ic_browser,
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
                        glyph = R.drawable.ic_speaker,
                    )
                }
                row { shape ->
                    SettingRow(
                        stringResource(R.string.settings_volume_limit),
                        shape,
                        subtitle = limitLabel(state.volumeLimit),
                        role = Role.DropdownList,
                        onClick = { choosing = SettingsSheet.VolumeLimit },
                        glyph = R.drawable.ic_volume,
                    )
                }
                row { shape ->
                    SettingRow(
                        stringResource(R.string.settings_sleep),
                        shape,
                        subtitle = sleepLine(state),
                        onClick = onSleepTimer,
                        glyph = R.drawable.ic_sleep,
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
                    SettingRow(
                        stringResource(R.string.hints),
                        shape,
                        subtitle = hints,
                        onClick = onHints,
                        glyph = R.drawable.ic_tip,
                    )
                }
            }
            SettingsGroup(stringResource(R.string.settings_general), above = BetweenGroups) {
                row { shape ->
                    SettingRow(
                        stringResource(R.string.settings_appearance),
                        shape,
                        subtitle = stringResource(R.string.settings_appearance_detail),
                        onClick = onAppearance,
                        glyph = R.drawable.ic_palette,
                    )
                }
                row { shape ->
                    SettingRow(
                        stringResource(R.string.downloads),
                        shape,
                        subtitle = downloadsLine(downloads.account, downloads.usedBytes),
                        onClick = { choosing = SettingsSheet.Downloads },
                        glyph = R.drawable.ic_download,
                    )
                }
                row { shape ->
                    val waiting = update is UpdateState.Available || update is UpdateState.Downloading
                    SettingRow(
                        stringResource(R.string.settings_updates),
                        shape,
                        subtitle = when (update) {
                            is UpdateState.Available -> stringResource(R.string.update_out, update.version)

                            is UpdateState.Downloading -> stringResource(
                                R.string.update_downloading,
                                update.update.version,
                            )

                            UpdateState.Newest -> stringResource(R.string.settings_version_newest, state.appVersion)

                            else -> stringResource(R.string.settings_version, state.appVersion)
                        },
                        onClick = { choosing = SettingsSheet.Updates },
                        glyph = R.drawable.ic_updates,
                        mark = if (waiting) ({ MossDot() }) else null,
                    )
                }
                row { shape ->
                    SettingRow(
                        stringResource(R.string.licences),
                        shape,
                        subtitle = stringResource(R.string.licences_detail),
                        onClick = onLicences,
                        glyph = R.drawable.ic_licences,
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
                SettingsSheet.Updates -> UpdatesSheetContent(
                    update,
                    state.appVersion,
                    checkAtStart = state.lookForUpdates,
                    onCheckAtStartChange = onCheckForUpdatesChange,
                    onCheck = onCheckForUpdate,
                    onInstall = onInstallUpdate,
                )

                SettingsSheet.Downloads -> DownloadsSheetContent(
                    downloads.copy(wifiOnly = state.downloadOnWifi),
                    onDownloadOnWifiChange,
                    onAbsSignIn,
                    onAbsSignOut,
                )

                SettingsSheet.SignOut -> SheetQuestion(
                    title = stringResource(R.string.settings_sign_out_title),
                    question = stringResource(R.string.settings_sign_out_question),
                    primary = stringResource(R.string.settings_sign_out),
                    onPrimary = { close(onSignOut) },
                    secondary = stringResource(R.string.player_confirm_cancel),
                    onSecondary = { close() },
                )

                SettingsSheet.VolumeLimit -> ChoiceSheet(
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

/** From the big title's baseline, where Settings are a place of their own, to the first group's name. */
private val TitleToGroup = Space.S8

private enum class SettingsSheet { VolumeLimit, Updates, Downloads, SignOut }

@Composable
private fun limitLabel(level: Int): String =
    if (level == 0) stringResource(R.string.sleep_off) else stringResource(R.string.player_volume_percent, level)

/** No limit, or one of these: loud enough for a room, short of what startles. */
private val LIMITS = listOf(0, 40, 50, 60, 70, 80)

/** Settings, opened from Home's pull with the way back, or as a place of its own in the bar ([asPlace]). */
@Composable
fun SettingsRoute(navigator: Navigator, modifier: Modifier = Modifier, asPlace: Boolean = false) {
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
        onBack = if (asPlace) null else ({ navigator.back() }),
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
        onCheckForUpdatesChange = viewModel::onCheckForUpdatesChange,
        onCheckForUpdate = graph.updates::checkNow,
        onInstallUpdate = graph.updates::install,
        downloads = DownloadSettings(account, used, last = prefs?.lastAbsAccount()),
        onDownloadOnWifiChange = viewModel::onDownloadOnWifiChange,
        onAbsSignIn = graph.downloading::signIn,
        onAbsSignOut = graph.downloading::signOut,
        onLicences = { navigator.open(Route.Licences) },
        modifier = modifier,
    )
}
