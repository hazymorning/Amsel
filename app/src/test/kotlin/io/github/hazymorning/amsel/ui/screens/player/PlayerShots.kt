package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.app.GroupChoice
import io.github.hazymorning.amsel.app.SleepMode
import io.github.hazymorning.amsel.app.SleepTimerState
import io.github.hazymorning.amsel.data.ServerExtras
import io.github.hazymorning.amsel.sample.PlayerSample
import io.github.hazymorning.amsel.sample.SampleData
import io.github.hazymorning.amsel.testing.ScreenshotTest
import io.github.hazymorning.amsel.testing.ShotVariant
import io.github.hazymorning.amsel.testing.ShotVariant.LargeFont
import io.github.hazymorning.amsel.testing.ShotVariant.Light
import io.github.hazymorning.amsel.ui.components.label
import io.github.hazymorning.amsel.ui.model.ActionsContext
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.ItemActionsUiState
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import org.junit.Test

class PlayerShots : ScreenshotTest() {
    /** Playing, shuffle and repeat on; colours derived from the sample cover through Coil and Palette, as in the app. */
    @Test fun nowPlaying() = snap("now_playing", ShotVariant.entries) { NowPlaying(PlayerSample.nowPlaying) }

    /**
     * A book: back 15 and forward 30 for shuffle and repeat, the chapter under the title, the times
     * under the line, Chapters for Up next.
     */
    @Test fun nowPlayingBook() = snap("now_playing_book", ShotVariant.entries) { NowPlaying(PlayerSample.book) }

    /** A seek on its way: the place sought shows, the line fainter until the speaker is there. */
    @Test fun nowPlayingBookSeeking() = snap("now_playing_book_seeking") {
        NowPlaying(PlayerSample.book.copy(arriving = true))
    }

    /**
     * In German on the phone itself, the speaker every new user starts with: the footer keeps its
     * name, the caret and the volume's level.
     */
    @Test fun nowPlayingOnThePhoneInGerman() = snap("now_playing_phone_german", qualifiers = "de") {
        NowPlaying(PlayerSample.nowPlaying.copy(speaker = phone))
    }

    /** An endless mix: named above the artwork where the album stands, shuffle and repeat as the server has them. */
    @Test fun nowPlayingMix() = snap("now_playing_mix", ShotVariant.entries) {
        NowPlaying(PlayerSample.nowPlaying.copy(mix = "Blue Hour", canShuffle = false))
    }

    /** A finger halfway through a swipe to the next song: the cover follows and fades, and keeps its shade. */
    @Test fun nowPlayingSwiping() = snap(
        "now_playing_swiping",
        before = {
            onRoot().performTouchInput {
                down(Offset(centerX, height * 0.3f))
                repeat(6) { moveBy(Offset(-60f, 0f)) }
            }
        },
    ) { NowPlaying(PlayerSample.nowPlaying) }

    @Test fun upNext() = snap("up_next") { UpNext(UpNextUiState(PlayerSample.upNext, firstEditable = 2)) }

    /**
     * Kitchen plays and carries the ring; the Bedroom rests on a playlist. No speakers play along:
     * Group, on the heading's line, offers to make a group for the speaker in use.
     */
    @Test fun playOn() = snapScreen("play_on", ShotVariant.entries) {
        OverNowPlaying { PlayOn(PlayOnUiState(PlayerSample.speakers, "kitchen", kitchen, group(members = false))) }
    }

    /** Two play along with Kitchen: Group names how many, and Ungroup stands before it, a glyph alone. */
    @Test fun playOnAGroup() = snapScreen("play_on_group", ShotVariant.entries) { OverNowPlaying { PlayOn(grouped) } }

    /** The same in German, where the words are longer: still one line. */
    @Test fun playOnAGroupInGerman() = snapScreen("play_on_group_german", qualifiers = "de") {
        OverNowPlaying { PlayOn(grouped) }
    }

    @Test fun playOnAsks() = snapScreen("play_on_question", listOf(Light, LargeFont)) {
        val speakers = PlayerSample.speakers
        val question = PlayOnQuestion(speakers.first { it.isPlaying }, "Moby-Dick", speakers.first { it.isPhone })
        OverNowPlaying { PlayOn(PlayOnUiState(speakers, currentId = "phone", question = question)) }
    }

    @Test fun itemActionsTrack() = snapScreen("item_actions_track", listOf(Light, LargeFont)) {
        OverNowPlaying {
            ItemActionsSheetContent(
                ItemActionsUiState.forTrack(playlistTrack, ActionsContext.EditablePlaylist, extras),
                onAction = {},
            )
        }
    }

    /** Now Playing's menu where the server has a DJ: the third of the speaker's rows, before the song's own. */
    @Test fun itemActionsNowPlaying() = snapScreen("item_actions_now_playing", listOf(Light, LargeFont)) {
        val target = PlayerSample.nowPlaying.moreTarget() as ActionTarget.Track
        OverNowPlaying {
            ItemActionsSheetContent(
                ItemActionsUiState.forTrack(target.track, target.context, extras, dj = true),
                onAction = {},
            )
        }
    }

    /** Off, then the server's hosts under their names; the one that talks is ticked. */
    @Test fun djSheet() = snapScreen("dj_sheet", ShotVariant.entries) {
        OverNowPlaying { DjSheetContent("Kitchen", PlayerSample.djHosts, current = "music_nerd", onPick = {}) }
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

    /** What the DJ says stands among the songs under the server's name for it, over "DJ". */
    @Test fun upNextDj() = snap("up_next_dj") { UpNext(UpNextUiState(PlayerSample.upNextWithDj, firstEditable = 2)) }

    @Test fun addToPlaylist() = snapScreen("add_to_playlist") {
        OverNowPlaying { PlaylistPicker(SampleData.playlists, onPick = {}) }
    }
}

private val extras = ServerExtras(mix = true, similarSongs = true)

private val phone = PlayerSample.speakers.first { it.isPhone }

private val kitchen = PlayerSample.speakers.first { it.id == "kitchen" }

private fun group(members: Boolean) = listOf(
    GroupChoice("bedroom", "Bedroom", isPhone = false, member = members),
    GroupChoice("living", "Living room", isPhone = false, member = members),
    GroupChoice("phone", "This phone", isPhone = true, member = false),
)

/** Kitchen leads the Bedroom and the Living room: one row for the three. */
private val grouped = PlayOnUiState(
    PlayerSample.speakers.filter { it.id != "living" && it.id != "bedroom" }
        .map { if (it.id == "kitchen") it.copy(others = 2) else it },
    currentId = "kitchen",
    leader = kitchen,
    group = group(members = true),
    grouped = true,
)

private val playlistTrack = TrackUi(
    "library://track/2",
    "The Quiet Part",
    "Marlene Vogt",
    "Blue Hour",
    ArtRef("sample:album/1"),
    187,
    favorite = true,
    albumKey = "library://album/1",
    artistKey = "library://artist/3",
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
