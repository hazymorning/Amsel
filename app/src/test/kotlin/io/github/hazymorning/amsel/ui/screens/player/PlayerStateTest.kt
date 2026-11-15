package io.github.hazymorning.amsel.ui.screens.player

import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.PlayerMedia
import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.data.ServerExtras
import io.github.hazymorning.amsel.sample.PlayerSample
import io.github.hazymorning.amsel.sample.SampleData
import io.github.hazymorning.amsel.ui.components.moved
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.ActionsContext
import io.github.hazymorning.amsel.ui.model.ItemAction.AddToFavorites
import io.github.hazymorning.amsel.ui.model.ItemAction.AddToPlaylist
import io.github.hazymorning.amsel.ui.model.ItemAction.AddToUpNext
import io.github.hazymorning.amsel.ui.model.ItemAction.Dj
import io.github.hazymorning.amsel.ui.model.ItemAction.EndlessMix
import io.github.hazymorning.amsel.ui.model.ItemAction.GoToAlbum
import io.github.hazymorning.amsel.ui.model.ItemAction.GoToArtist
import io.github.hazymorning.amsel.ui.model.ItemAction.Play
import io.github.hazymorning.amsel.ui.model.ItemAction.PlayNext
import io.github.hazymorning.amsel.ui.model.ItemAction.RemoveFromFavorites
import io.github.hazymorning.amsel.ui.model.ItemAction.RemoveFromPlaylist
import io.github.hazymorning.amsel.ui.model.ItemAction.RemoveFromUpNext
import io.github.hazymorning.amsel.ui.model.ItemAction.Shuffle
import io.github.hazymorning.amsel.ui.model.ItemAction.SimilarSongs
import io.github.hazymorning.amsel.ui.model.ItemAction.SleepTimer
import io.github.hazymorning.amsel.ui.model.ItemAction.TransferPlayback
import io.github.hazymorning.amsel.ui.model.ItemActionsUiState
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.next
import io.github.hazymorning.amsel.ui.model.positionAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateTest {
    private val playing = SampleData.nowPlaying.copy(positionMs = 10_000, positionAtMs = 1_000, durationMs = 60_000)

    /** A server with all it takes for a mix and similar songs, as one is out of the box. */
    private val extras = ServerExtras(mix = true, similarSongs = true)

    @Test fun positionMovesOnWhilePlaying() = assertEquals(15_000, playing.positionAt(6_000))

    @Test fun positionHoldsWhilePaused() = assertEquals(10_000, playing.copy(isPlaying = false).positionAt(6_000))

    @Test fun positionStopsAtTheEnd() = assertEquals(60_000, playing.positionAt(500_000))

    @Test fun positionWithoutDurationKeepsCounting() = assertEquals(
        15_000,
        playing.copy(durationMs = 0).positionAt(6_000),
    )

    @Test fun positionMovesAtTheItemsSpeed() = assertEquals(17_500, playing.copy(speed = 1.5f).positionAt(6_000))

    @Test fun aBookOpensItsSheetWithTheWayToItsPage() {
        val target = PlayerSample.book.moreTarget() as ActionTarget.Media
        assertEquals(MediaKind.Audiobook, target.item.kind)
        assertEquals(ActionsContext.NowPlaying, target.context)
        assertEquals("library://artist/31", target.item.artistKey)
    }

    @Test fun repeatCyclesOffAllOne() {
        assertEquals(RepeatMode.All, RepeatMode.Off.next())
        assertEquals(RepeatMode.One, RepeatMode.All.next())
        assertEquals(RepeatMode.Off, RepeatMode.One.next())
    }

    @Test fun movedKeepsTheOtherItemsInOrder() {
        assertEquals(listOf("b", "c", "a", "d"), listOf("a", "b", "c", "d").moved(0, 2))
        assertEquals(listOf("a", "d", "b", "c"), listOf("a", "b", "c", "d").moved(3, 1))
        assertEquals(listOf("a", "b"), listOf("a", "b").moved(0, 5))
    }

    @Test fun upNextOpensOnTheCurrentSong() {
        assertEquals(1, UpNextUiState(PlayerSample.upNext).currentIndex)
        val fourth = PlayerSample.upNext.mapIndexed { i, item -> item.copy(isCurrent = i == 3) }
        assertEquals(3, UpNextUiState(fourth).currentIndex)
        assertEquals(0, UpNextUiState(PlayerSample.upNext.map { it.copy(isCurrent = false) }).currentIndex)
        assertEquals(0, UpNextUiState().currentIndex)
        assertEquals(true, UpNextUiState().isEmpty)
        assertEquals(false, UpNextUiState(loading = true).isEmpty)
    }

    @Test fun aSpeakerNamesItsSongApartFromItsArtist() {
        fun speaker(title: String?, artist: String?) = UiMapper {
            null
        }.speaker(Player("k", currentMedia = PlayerMedia(title = title, artist = artist)), isPhone = false)
        assertEquals("Lanterns" to "Marlene Vogt", speaker("Lanterns", "Marlene Vogt").let { it.song to it.artist })
        assertEquals(null, speaker(" ", "Marlene Vogt").song)
        assertEquals(null, speaker(null, null).song)
    }

    @Test fun songActionsInTheLibrary() {
        val track = SampleData.tracks[0].copy(albumKey = "album", artistKey = "artist")
        assertEquals(
            listOf(PlayNext, AddToUpNext, EndlessMix, AddToFavorites) +
                listOf(AddToPlaylist, GoToArtist, GoToAlbum, SimilarSongs),
            ItemActionsUiState.forTrack(track, extras = extras).actions,
        )
        assertEquals(
            listOf(PlayNext, AddToUpNext, AddToFavorites, AddToPlaylist, GoToArtist, GoToAlbum),
            ItemActionsUiState.forTrack(track).actions,
        )
    }

    @Test fun songActionsInUpNextOfferRemovalInsteadOfAdding() {
        val track = SampleData.tracks[1]
        assertEquals(
            listOf(PlayNext, EndlessMix, RemoveFromFavorites, AddToPlaylist, SimilarSongs, RemoveFromUpNext),
            ItemActionsUiState.forTrack(track, ActionsContext.UpNext, extras).actions,
        )
        assertEquals(
            listOf(PlayNext, RemoveFromFavorites, AddToPlaylist, RemoveFromUpNext),
            ItemActionsUiState.forTrack(track, ActionsContext.UpNext).actions,
        )
    }

    @Test fun songActionsInAnEditablePlaylist() {
        val track = SampleData.tracks[0]
        val context = ActionsContext.EditablePlaylist
        assertEquals(RemoveFromPlaylist, ItemActionsUiState.forTrack(track, context).actions.last())
        val all = ItemActionsUiState.forTrack(track, context, extras).actions
        assertEquals(listOf(SimilarSongs, RemoveFromPlaylist), all.takeLast(2))
    }

    @Test fun songHeaderNamesArtistAndAlbum() =
        assertEquals("Marlene Vogt · Blue Hour", ItemActionsUiState.forTrack(SampleData.tracks[0]).subtitle)

    @Test fun albumActions() {
        val album = SampleData.albums[2].copy(artistKey = "library://artist/3")
        val state = ItemActionsUiState.forMedia(album, extras = extras)
        assertEquals(MediaKind.Album, state.kind)
        assertEquals(
            listOf(Play, Shuffle, PlayNext, AddToUpNext, EndlessMix, RemoveFromFavorites, AddToPlaylist, GoToArtist),
            state.actions,
        )
        assertEquals(
            listOf(Play, Shuffle, PlayNext, AddToUpNext, RemoveFromFavorites, AddToPlaylist, GoToArtist),
            ItemActionsUiState.forMedia(album).actions,
        )
    }

    @Test fun anItemsOwnPageKeepsPlayAndShuffleToItsButtons() {
        val album = SampleData.albums[2]
        assertEquals(
            listOf(PlayNext, AddToUpNext, EndlessMix, RemoveFromFavorites, AddToPlaylist),
            ItemActionsUiState.forMedia(album, context = ActionsContext.Page, extras = extras).actions,
        )
    }

    @Test fun whatATapPlaysIsNotStartedFromTheSheet() {
        val starts = setOf(Play, Shuffle)
        val station = SampleData.playlists[0].copy(key = "library://radio/1", kind = MediaKind.Radio)
        assertTrue(ItemActionsUiState.forMedia(station).actions.none { it in starts })
        assertTrue(ItemActionsUiState.forTrack(SampleData.tracks[0]).actions.none { it in starts })
    }

    @Test fun nowPlayingsMoreOpensTheSong() {
        val song = SampleData.nowPlaying.copy(favorite = true)
        val target = song.moreTarget() as ActionTarget.Track
        assertEquals("library://track/1", target.track.key)
        assertEquals("Lanterns", target.track.title)
        assertEquals("library://album/1", target.track.albumKey)
        assertEquals(ActionsContext.NowPlaying, target.context)
        assertEquals(
            listOf(SleepTimer, TransferPlayback, PlayNext, AddToUpNext, EndlessMix) +
                listOf(RemoveFromFavorites, AddToPlaylist, GoToArtist, GoToAlbum, SimilarSongs),
            ItemActionsUiState.forTrack(target.track, target.context, extras).actions,
        )
        // Where the server has a DJ, it is the third of the speaker's rows.
        assertEquals(
            listOf(SleepTimer, TransferPlayback, Dj, PlayNext, AddToUpNext, EndlessMix) +
                listOf(RemoveFromFavorites, AddToPlaylist, GoToArtist, GoToAlbum, SimilarSongs),
            ItemActionsUiState.forTrack(target.track, target.context, extras, dj = true).actions,
        )
    }

    @Test fun whatTheDjSaysOpensNowPlayingsMenuAsItself() {
        val words = SampleData.nowPlaying.copy(key = "ai_radio://sound_effect/dj_005", djBreak = true)
        val target = words.moreTarget() as ActionTarget.Track
        assertTrue(target.track.djBreak)
        assertEquals(ActionsContext.NowPlaying, target.context)
        assertNull(words.heart(canWrite = true))
        assertEquals(false, SampleData.nowPlaying.heart(canWrite = true))
    }

    @Test fun nowPlayingsMoreOpensAStationAsAStation() {
        val station = SampleData.nowPlaying.copy(key = "library://radio/1", kind = MediaKind.Radio, album = null)
        val target = station.moreTarget() as ActionTarget.Media
        assertEquals(MediaKind.Radio, target.item.kind)
        assertEquals("library://radio/1", target.item.key)
    }

    @Test fun nowPlayingHasNoMoreUntilTheSongIsKnown() = assertNull(SampleData.nowPlaying.copy(key = null).moreTarget())

    @Test fun artistAndPlaylistActions() {
        assertEquals(
            listOf(Play, Shuffle, PlayNext, AddToUpNext, EndlessMix, AddToFavorites),
            ItemActionsUiState.forMedia(SampleData.artists[0], extras = extras).actions,
        )
        assertEquals(
            listOf(Play, Shuffle, PlayNext, AddToUpNext, EndlessMix, RemoveFromFavorites),
            ItemActionsUiState.forMedia(SampleData.playlists[0], extras = extras).actions,
        )
    }
}
