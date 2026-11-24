package io.github.hazymorning.amsel.ui

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.LibrarySort
import io.github.hazymorning.amsel.app.Offer
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.sample.LibrarySampleData
import io.github.hazymorning.amsel.sample.PlayerSample
import io.github.hazymorning.amsel.sample.SampleData
import io.github.hazymorning.amsel.testing.ScreenshotTest
import io.github.hazymorning.amsel.testing.ShotVariant
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.navigation.BarItem
import io.github.hazymorning.amsel.ui.navigation.DockControl
import io.github.hazymorning.amsel.ui.screens.library.LibraryKind
import io.github.hazymorning.amsel.ui.screens.library.LibraryScreen
import io.github.hazymorning.amsel.ui.screens.library.LibraryUiState
import io.github.hazymorning.amsel.ui.screens.player.NowPlayingScreen
import org.junit.Test

class ShellShots : ScreenshotTest() {
    /** The edge fades of the slim bar and the dock over a scrolled list. */
    @Test fun scrolled() = snap(
        "shell_scrolled",
        before = {
            openLibrary()
            onRoot().performTouchInput {
                swipeUp(startY = height * 0.75f, endY = height * 0.45f, durationMillis = 1_000)
            }
        },
    ) { Shell(rows = true) }

    /** The dock's speaker button stays while nothing plays, to choose where. */
    @Test fun idleSpeaker() = snap("shell_idle_speaker", before = {
        openLibrary()
    }) { Shell(playing = false, dock = listOf(DockControl.PlayPause, DockControl.Next, DockControl.Speaker)) }

    /** In German, whose words are longest: Retry stays beside the sentence, and no word breaks. */
    @Test fun messageInGerman() = snap("shell_message_german", qualifiers = "de", before = { openLibrary() }) {
        val messages = remember { SnackbarHostState() }
        val sentence = stringResource(PlaybackR.string.error_speaker_busy, PlayerSample.nowPlaying.speaker.name)
        val retry = stringResource(R.string.retry)
        LaunchedEffect(messages) { messages.showSnackbar(sentence, retry, duration = SnackbarDuration.Indefinite) }
        Shell(messages = messages)
    }

    @Test fun offer() = snap("shell_offer", listOf(ShotVariant.Light, ShotVariant.LargeFont), before = {
        openLibrary()
    }) { Shell(offer = station) }

    private val station = Offer(
        about = "library://radio/13",
        title = "Blue Note Radio",
        button = R.string.play,
        art = ArtRef("sample:radio/13"),
        kind = MediaKind.Radio,
        take = {},
    )

    private fun SemanticsNodeInteractionsProvider.openLibrary() = openPlace(PlaybackR.string.library)

    private fun SemanticsNodeInteractionsProvider.openPlace(label: Int) {
        val name = ApplicationProvider.getApplicationContext<Context>().getString(label)
        onNode(isSelectable() and hasContentDescription(name)).performClick()
    }
}

@Composable
private fun Shell(
    layer: PlayerLayerState = remember { PlayerLayerState(open = false) },
    rows: Boolean = false,
    playing: Boolean = true,
    messages: SnackbarHostState = remember { SnackbarHostState() },
    offer: Offer? = null,
    dock: List<DockControl> = DockControl.Default.visible,
    bar: List<BarItem> = BarItem.Default,
) {
    AppShell(
        nowPlaying = PlayerSample.nowPlaying.takeIf { playing },
        speaker = PlayerSample.nowPlaying.speaker.copy(isPlaying = playing),
        onSpeaker = {},
        snackbarHostState = messages,
        layer = layer,
        offer = offer,
        dock = dock,
        bar = bar,
        slots = ShellSlots(
            screen = { _, _ -> Library(rows, playing) },
            nowPlaying = { onClose, _ ->
                NowPlayingScreen(
                    PlayerSample.nowPlaying,
                    onControl = {},
                    onClose = onClose,
                    onMore = {},
                    onArtist = {},
                    onSpeaker = {},
                    onUpNext = {},
                )
            },
            sheets = {},
        ),
    )
}

/** [rows]: the artists, a list long enough to scroll. */
@Composable
private fun Library(rows: Boolean, playing: Boolean) {
    val state = if (rows) {
        LibraryUiState(LibraryKind.Artists, LibrarySort.AToZ, LibrarySampleData.artistsAToZ)
    } else {
        val blueHour = SampleData.albums.first().key
        LibraryUiState(
            LibraryKind.Albums,
            LibrarySort.RecentlyAdded,
            SampleData.albums,
            playing = if (playing) setOf(blueHour) else emptySet(),
        )
    }
    LibraryScreen(state, {}, {}, {}, {}, {}, {})
}
