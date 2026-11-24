package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.runtime.Composable
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.app.GroupCandidate
import io.github.hazymorning.amsel.app.MemberVolume
import io.github.hazymorning.amsel.app.SleepMode
import io.github.hazymorning.amsel.app.SleepTimerState
import io.github.hazymorning.amsel.data.ServerExtras
import io.github.hazymorning.amsel.sample.PlayerSample
import io.github.hazymorning.amsel.testing.ScreenshotTest
import io.github.hazymorning.amsel.testing.ShotVariant.Dark
import io.github.hazymorning.amsel.testing.ShotVariant.LargeFont
import io.github.hazymorning.amsel.testing.ShotVariant.Light
import io.github.hazymorning.amsel.ui.components.label
import io.github.hazymorning.amsel.ui.model.ItemActionsUiState
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import org.junit.Test

class PlayerShots : ScreenshotTest() {
    // Colours come from the sample cover through Coil and Palette, as in the app.
    @Test fun nowPlaying() = snap("now_playing", listOf(Light, Dark)) { NowPlaying(PlayerSample.nowPlaying) }

    // Skip buttons for shuffle and repeat, the chapter heading the title block, the times under the line,
    // Chapters for Up next.
    @Test fun nowPlayingBook() = snap("now_playing_book", listOf(Light, LargeFont)) { NowPlaying(PlayerSample.book) }

    @Test fun upNext() = snap("up_next") { UpNext(UpNextUiState(PlayerSample.upNext, firstEditable = 2)) }

    // No group yet: Group, under the speakers, offers one for the speaker in use.
    @Test fun playOn() = snapScreen("play_on") {
        OverNowPlaying { PlayOn(PlayOnUiState(PlayerSample.speakers, "kitchen", kitchen, alongside)) }
    }

    @Test fun volumeOfAGroup() = snapScreen("volume_group") {
        OverNowPlaying {
            VolumeSheetContent(
                "Kitchen +2",
                level = 40,
                members = listOf(
                    MemberVolume("kitchen", "Kitchen", 40),
                    MemberVolume("bedroom", "Bedroom", 25),
                    MemberVolume("living", "Living room", 70),
                ),
                onLevel = {},
                onMember = { _, _ -> },
            )
        }
    }

    // At 200 % the second button's label takes two lines in a pill that rounds less.
    @Test fun playOnAsks() = snapScreen("play_on_question", listOf(Light, LargeFont)) {
        val speakers = PlayerSample.speakers
        val question = PlayOnQuestion(speakers.first { it.isPlaying }, "Moby-Dick", speakers.first { it.isPhone })
        OverNowPlaying { PlayOn(PlayOnUiState(speakers, currentId = "phone", question = question)) }
    }

    // With a DJ on the server: the DJ is the third of the speaker's rows, before the song's own.
    @Test fun itemActionsNowPlaying() = snapScreen("item_actions_now_playing") {
        val target = PlayerSample.nowPlaying.moreTarget() as ActionTarget.Track
        OverNowPlaying {
            ItemActionsSheetContent(
                ItemActionsUiState.forTrack(target.track, target.context, extras, dj = true),
                onAction = {},
            )
        }
    }

    // The chapter's end is the longest length: the title keeps its line, the length goes on the line
    // under it.
    @Test fun sleepTimerInGerman() = snapScreen("sleep_timer_german", qualifiers = "de") {
        NowPlaying(PlayerSample.book.copy(speaker = phone))
        AmselSheet(onDismiss = {}) {
            SleepSheetContent(
                phone.label(),
                SleepTimerState(SleepMode.EndOfItem, endsAtMs = null),
                R.string.sleep_end_of_chapter,
                onSet = {},
                onOff = {},
            )
        }
    }
}

private val extras = ServerExtras(mix = true, similarSongs = true)

private val phone = PlayerSample.speakers.first { it.isPhone }

private val kitchen = PlayerSample.speakers.first { it.id == "kitchen" }

// Could play along with Kitchen; none does.
private val alongside = listOf(
    GroupCandidate("bedroom", "Bedroom", isPhone = false, member = false),
    GroupCandidate("living", "Living room", isPhone = false, member = false),
    GroupCandidate("phone", "This phone", isPhone = true, member = false),
)

@Composable
private fun NowPlaying(state: NowPlayingUi) = NowPlayingScreen(
    state,
    onControl = {},
    onClose = {},
    onMore = {},
    onArtist = {},
    onSpeaker = {},
    onUpNext = {},
)

@Composable
private fun UpNext(state: UpNextUiState) = UpNextScreen(
    state,
    onClose = {},
    onPlay = {},
    onMove = { _, _ -> },
    onRemove = {},
    onMore = {},
    onRetry = {},
)

@Composable
private fun PlayOn(state: PlayOnUiState) = PlayOnSheetContent(state, onSelect = {})

@Composable
private fun OverNowPlaying(content: @Composable () -> Unit) {
    NowPlaying(PlayerSample.nowPlaying)
    AmselSheet(onDismiss = {}) { content() }
}
