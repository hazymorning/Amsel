package io.github.hazymorning.amsel.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.data.home.HomeLayout
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.navigation.BarItem
import io.github.hazymorning.amsel.ui.navigation.DockControl
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.Route
import io.github.hazymorning.amsel.ui.screens.library.LibraryKind
import io.github.hazymorning.amsel.ui.screens.library.label
import io.github.hazymorning.amsel.ui.screens.library.libraryKinds
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.ThemeMode

/** The arrange rows follow the screen from top to bottom: the pages, the mini player, the bar. */
@Composable
fun AppearanceScreen(
    theme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    /** Null before Android 13, which has no per-app language page. */
    onLanguage: (() -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    homeSections: Int = HomeLayout.DEFAULT_SECTIONS.size,
    onHome: () -> Unit = {},
    library: List<LibraryKind> = libraryKinds(LibraryKind.Default.visible, held = emptySet()),
    onLibrary: () -> Unit = {},
    dock: List<DockControl> = DockControl.Default.visible,
    onDock: () -> Unit = {},
    bar: List<BarItem> = BarItem.Default,
    onBar: () -> Unit = {},
) {
    val scroll = rememberScrollState()
    var choosing by rememberSaveable { mutableStateOf(false) }
    MaterialScreen(modifier, bar = { PageTopBar(stringResource(R.string.settings_appearance), onBack) }) { insets ->
        ScrollEdges(scroll)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(insets).padding(bottom = Space.ListEnd)) {
            PageHint(stringResource(R.string.appearance_intro))
            SettingsBlock {
                row { shape ->
                    ValueRow(stringResource(R.string.settings_theme), stringResource(themeLabel(theme)), shape) {
                        choosing = true
                    }
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
            }
            SettingsGroup(stringResource(R.string.settings_arrange), above = BetweenGroups) {
                row { shape ->
                    SettingRow(
                        stringResource(R.string.tab_home),
                        shape,
                        subtitle = pluralStringResource(R.plurals.settings_home_sections, homeSections, homeSections),
                        onClick = onHome,
                    )
                }
                row { shape ->
                    val names = library.map { stringResource(it.label) }.joinToString(", ")
                    SettingRow(stringResource(PlaybackR.string.library), shape, subtitle = names, onClick = onLibrary)
                }
                row { shape ->
                    val names = dock.map { stringResource(it.label) }.joinToString(", ")
                    SettingRow(stringResource(R.string.settings_dock), shape, subtitle = names, onClick = onDock)
                }
                row { shape ->
                    val names = bar.map { stringResource(it.label) }.joinToString(", ")
                    SettingRow(stringResource(R.string.settings_bar), shape, subtitle = names, onClick = onBar)
                }
            }
        }
    }
    if (choosing) {
        AmselSheet(onDismiss = { choosing = false }) {
            ChoiceSheet(
                stringResource(R.string.settings_theme),
                ThemeMode.entries,
                theme,
                label = { stringResource(themeLabel(it)) },
                onPick = {
                    onThemeChange(it)
                    close()
                },
            )
        }
    }
}

@StringRes
private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.System -> R.string.settings_theme_system
    ThemeMode.Light -> R.string.settings_theme_light
    ThemeMode.Dark -> R.string.settings_theme_dark
}

@Composable
fun AppearanceRoute(navigator: Navigator) {
    val graph = LocalContext.current.graph
    val context = LocalContext.current
    val viewModel = rememberSettingsViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by graph.data.prefs.prefs.collectAsStateWithLifecycle()
    val held by graph.data.library.heldTypes.collectAsStateWithLifecycle(emptySet())
    val home by graph.data.home.layout.collectAsStateWithLifecycle()
    val downloaded by graph.downloading.rows.collectAsStateWithLifecycle(emptyList())
    AppearanceScreen(
        theme = state.theme,
        onThemeChange = viewModel::onThemeChange,
        onLanguage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            { context.openLanguageSettings() }
        } else {
            null
        },
        onBack = navigator::back,
        homeSections = (home ?: HomeLayout()).sections.size,
        onHome = { navigator.open(Route.HomeEdit) },
        library = libraryKinds(LibraryKind.arrangement(prefs?.libraryKinds).visible, held, downloaded.isNotEmpty()),
        onLibrary = { navigator.open(Route.LibraryKinds) },
        dock = DockControl.arrangement(prefs?.dockControls).visible,
        onDock = { navigator.open(Route.Dock) },
        bar = BarItem.of(prefs?.barPlaces),
        onBar = { navigator.open(Route.Bar) },
    )
}

/**
 * The system page offers the languages in `locales_config.xml`. Some devices lack it; the app's
 * details page answers the tap there.
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
