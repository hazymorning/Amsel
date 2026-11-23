package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.runtime.Composable
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.app.GroupChoice
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
    /** Playing, shuffle on; colours derived from the sample cover through Coil and Palette, as in the app. */
    @Test fun nowPlaying() = snap("now_playing", listOf(Light, Dark)) { NowPlaying(PlayerSample.nowPlaying) }

    /**
     * A book: back 15 and forward 30 for shuffle and repeat, the chapter under the title, the times
     * under the line, Chapters for Up next.
     */
    @Test fun nowPlayingBook() = snap("now_playing_book", listOf(Light, LargeFont)) { NowPlaying(PlayerSample.book) }

    @Test fun upNext() = snap("up_next") { UpNext(UpNextUiState(PlayerSample.upNext, firstEditable = 2)) }

    /**
     * Kitchen plays and is ticked; the Bedroom rests on a playlist. No speakers play along: Group,
     * under the speakers, offers to make a group for the speaker in use.
     */
    @Test fun playOn() = snapScreen("play_on") {
        OverNowPlaying { PlayOn(PlayOnUiState(PlayerSample.speakers, "kitchen", kitchen, alongside)) }
    }

    /** A group: its bar, then one for each of its speakers under a quiet line. */
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

    /** At 200 % the second button's words take two lines in a pill that rounds less. */
    @Test fun playOnAsks() = snapScreen("play_on_question", listOf(Light, LargeFont)) {
        val speakers = PlayerSample.speakers
        val question = PlayOnQuestion(speakers.first { it.isPlaying }, "Moby-Dick", speakers.first { it.isPhone })
        OverNowPlaying { PlayOn(PlayOnUiState(speakers, currentId = "phone", question = question)) }
    }

    /** Now Playing's menu where the server has a DJ: the third of the speaker's rows, before the song's own. */
    @Test fun itemActionsNowPlaying() = snapScreen("item_actions_now_playing") {
        val target = PlayerSample.nowPlaying.moreTarget() as ActionTarget.Track
        OverNowPlaying {
            ItemActionsSheetContent(
                ItemActionsUiState.forTrack(target.track, target.context, extras, dj = true),
                onAction = {},
            )
        }
    }

    /**
     * The sleep timer in German on the phone, set to the chapter's end, its longest length: the
     * title has its line to itself, and the length stands on the line under it.
     */
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

/** The speakers that could play along with Kitchen; none does. */
private val alongside = listOf(
    GroupChoice("bedroom", "Bedroom", isPhone = false, member = false),
    GroupChoice("living", "Living room", isPhone = false, member = false),
    GroupChoice("phone", "This phone", isPhone = true, member = false),
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

/** The real sheet, in its own window over Now Playing, as the speaker button and ⋮ open it there. */
@Composable
private fun OverNowPlaying(content: @Composable () -> Unit) {
    NowPlaying(PlayerSample.nowPlaying)
    AmselSheet(onDismiss = {}) { content() }
}
