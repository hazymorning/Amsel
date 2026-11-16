package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performSemanticsAction
import io.github.hazymorning.amsel.data.downloads.AbsAccount
import io.github.hazymorning.amsel.sample.PlayerSample
import io.github.hazymorning.amsel.testing.ScreenshotTest
import io.github.hazymorning.amsel.testing.ShotVariant
import io.github.hazymorning.amsel.ui.AppShell
import io.github.hazymorning.amsel.ui.PlayerLayerState
import io.github.hazymorning.amsel.ui.ShellSlots
import io.github.hazymorning.amsel.ui.navigation.Arrangement
import io.github.hazymorning.amsel.ui.navigation.BarPlace
import io.github.hazymorning.amsel.ui.navigation.DockControl
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import org.junit.Test

class SettingsShots : ScreenshotTest() {
    @Test fun settings() = snap("settings", ShotVariant.entries) { Settings() }

    /** The page's end as the app shows it: the licence, and the blackbird on the dock's edge. */
    @Test fun foot() = snap(
        "settings_foot",
        ShotVariant.entries,
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

    /** The bottom bar's page over the shell, its bar following it: Search taken out. */
    @Test fun bar() = snap("settings_bar", ShotVariant.entries) {
        val places = BarPlace.arrangement(null).with(BarPlace.Search, on = false)
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

    /** The mini player's page over the shell, the dock following it: back, play and the heart. */
    @Test fun dock() = snap("settings_dock", ShotVariant.entries) {
        val controls = Arrangement(
            listOf(DockControl.Previous, DockControl.PlayPause, DockControl.Favorite) +
                DockControl.entries.filterNot {
                    it in setOf(DockControl.Previous, DockControl.PlayPause, DockControl.Favorite)
                },
            setOf(DockControl.Previous, DockControl.PlayPause, DockControl.Favorite),
        )
        AppShell(
            nowPlaying = PlayerSample.nowPlaying,
            speaker = PlayerSample.nowPlaying.speaker,
            onSpeaker = {},
            layer = remember { PlayerLayerState(open = false) },
            dock = controls.visible,
            slots = ShellSlots(
                screen = { _, _ -> DockScreen(controls, onChange = {}, onBack = {}) },
                nowPlaying = { _, _ -> },
                sheets = {},
            ),
        )
    }

    /** Suggestions: every moment on but the end of the queue. */
    @Test fun hints() = snap("settings_hints", listOf(ShotVariant.Light, ShotVariant.Dark)) {
        HintsScreen(off = setOf("ended"), onChange = {}, onBack = {})
    }

    /** Following the music, kept to some speakers: Hedwig left out. */
    @Test fun follow() = snap("settings_follow", ShotVariant.entries) {
        SettingsScreen(
            SettingsUiState(
                userName = "Anna",
                serverName = "Home",
                serverAddress = "192.168.1.20",
                appVersion = "0.1.0",
                followPlaying = true,
                followSkipped = setOf("hedwig"),
            ),
            onBack = {},
            onOpenWebUi = {},
            onSignOut = {},
            onThemeChange = {},
            onLanguage = {},
            followSpeakers = listOf(
                FollowSpeaker("gandalf", "Gandalf"),
                FollowSpeaker("hedwig", "Hedwig"),
                FollowSpeaker("office", "Mandaloffice"),
                FollowSpeaker("potter", "Potterbox"),
                FollowSpeaker("living", "Wohnzimmer"),
            ),
        )
    }

    /** Not signed in to Audiobookshelf yet: why, and its sign-in. */
    @Test fun downloadsSignIn() = snapScreen("downloads_sign_in", ShotVariant.entries) {
        Settings()
        AmselSheet(onDismiss = {}) {
            DownloadsSheetContent(DownloadSettings(), onWifiOnlyChange = {
            }, onSignIn = { _, _, _ -> null }, onSignOut = {})
        }
    }

    @Test fun downloadsSignedIn() = snapScreen("downloads_signed_in") {
        val account = AbsAccount("https://books.example.com", "anna")
        Settings(account)
        AmselSheet(onDismiss = {}) {
            DownloadsSheetContent(
                DownloadSettings(account, usedBytes = 1_288_490_188, wifiOnly = true),
                onWifiOnlyChange = {},
                onSignIn = { _, _, _ -> null },
                onSignOut = {},
            )
        }
    }
}

@Composable
private fun Settings(account: AbsAccount? = null) = SettingsScreen(
    SettingsUiState(
        userName = "Anna",
        serverName = "Home",
        serverAddress = "192.168.1.20",
        appVersion = "0.1.0",
    ),
    onBack = {},
    onOpenWebUi = {},
    onSignOut = {},
    onThemeChange = {},
    onLanguage = {},
    downloads = DownloadSettings(account),
)

/** Further than any page is long. */
private const val END = 100_000f
