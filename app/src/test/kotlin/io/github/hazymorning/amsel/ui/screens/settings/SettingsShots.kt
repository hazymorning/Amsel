package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performSemanticsAction
import io.github.hazymorning.amsel.sample.PlayerSample
import io.github.hazymorning.amsel.testing.ScreenshotTest
import io.github.hazymorning.amsel.testing.ShotVariant
import io.github.hazymorning.amsel.ui.AppShell
import io.github.hazymorning.amsel.ui.PlayerLayerState
import io.github.hazymorning.amsel.ui.ShellSlots
import io.github.hazymorning.amsel.ui.navigation.BarItem
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import org.junit.Test

class SettingsShots : ScreenshotTest() {
    /** At 200 %: rows grow, and Sign out stays beside the account. */
    @Test fun settings() = snap("settings", listOf(ShotVariant.LargeFont)) { Settings() }

    @Test fun settingsPlace() = snap("settings_place") {
        Settings(place = true)
    }

    /** Inside the shell, so the picture ends at the dock's edge. */
    @Test fun foot() = snap(
        "settings_foot",
        listOf(ShotVariant.Light, ShotVariant.Dark),
        before = { onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, END) } },
    ) {
        AppShell(
            nowPlaying = PlayerSample.nowPlaying,
            speaker = PlayerSample.nowPlaying.speaker,
            onSpeaker = {},
            layer = remember { PlayerLayerState(open = false) },
            slots = ShellSlots(screen = { _, _ -> Settings() }, nowPlaying = { _, _ -> }, sheets = {}),
        )
    }

    /** A full bar, so the places left out show as waiting; the shell's bar follows the page. */
    @Test fun bar() = snap("settings_bar") {
        val places = BarItem.arrangement(null)
            .with(BarItem.Search, on = false)
            .with(BarItem.Settings, on = false)
            .with(BarItem.Albums, on = true)
            .with(BarItem.Podcasts, on = true)
        AppShell(
            nowPlaying = PlayerSample.nowPlaying,
            speaker = PlayerSample.nowPlaying.speaker,
            onSpeaker = {},
            layer = remember { PlayerLayerState(open = false) },
            bar = places.visible,
            slots = ShellSlots(
                screen = { _, _ -> BarScreen(places, onChange = {}, onBack = {}) },
                nowPlaying = { _, _ -> },
                sheets = {},
            ),
        )
    }

    @Test fun downloadsSignIn() = snapScreen("downloads_sign_in", listOf(ShotVariant.Light, ShotVariant.Dark)) {
        Settings()
        AmselSheet(onDismiss = {}) {
            DownloadsSheetContent(DownloadSettings(), onWifiOnlyChange = {
            }, onSignIn = { _, _, _ -> null }, onSignOut = {})
        }
    }
}

@Composable
private fun Settings(place: Boolean = false) = SettingsScreen(
    SettingsUiState(
        userName = "Anna",
        serverName = "Home",
        serverAddress = "192.168.1.20",
        appVersion = "0.1.0",
    ),
    onBack = if (place) null else ({}),
    onOpenWebUi = {},
    onSignOut = {},
    downloads = DownloadSettings(),
)

private const val END = 100_000f
