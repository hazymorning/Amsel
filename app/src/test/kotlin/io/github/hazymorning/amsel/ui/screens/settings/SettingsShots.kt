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
import io.github.hazymorning.amsel.ui.navigation.BarPlace
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import org.junit.Test

class SettingsShots : ScreenshotTest() {
    /** The main page at 200 %, from Home with the way back: rows grow, Sign out stays beside the account. */
    @Test fun settings() = snap("settings", listOf(ShotVariant.LargeFont)) { Settings() }

    /** Settings as a place of their own in the bar: the big title, no way back. */
    @Test fun settingsPlace() = snap("settings_place") {
        Settings(place = true)
    }

    /** The page's end as the app shows it: the licence, and the blackbird on the dock's edge. */
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

    /**
     * The bottom bar's page over the shell, its bar following it: Search taken out, Albums and
     * Podcasts put in, so the bar is full and the other kinds wait.
     */
    @Test fun bar() = snap("settings_bar") {
        val places = BarPlace.arrangement(null)
            .with(BarPlace.Search, on = false)
            .with(BarPlace.Settings, on = false)
            .with(BarPlace.Albums, on = true)
            .with(BarPlace.Podcasts, on = true)
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

    /** Not signed in to Audiobookshelf yet: why, and its sign-in. */
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

/** Further than any page is long. */
private const val END = 100_000f
