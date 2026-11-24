package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.api.DjHost
import io.github.hazymorning.amsel.api.endlessMixUri
import io.github.hazymorning.amsel.data.ServerExtras
import io.github.hazymorning.amsel.data.downloads.AbsException
import io.github.hazymorning.amsel.data.downloads.DownloadState
import io.github.hazymorning.amsel.sample.PlayerSample
import io.github.hazymorning.amsel.sample.SampleData
import io.github.hazymorning.amsel.ui.model.ActionsContext
import io.github.hazymorning.amsel.ui.model.ItemAction
import io.github.hazymorning.amsel.ui.model.ItemActionsUiState
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import io.github.hazymorning.amsel.ui.screens.player.moreTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemActionControllerTest {
    private val extras = ServerExtras(mix = true, similarSongs = true)
    private val song = SampleData.tracks[0]
    private val album = SampleData.albums[0]

    private fun of(item: MediaUi, dynamicQueue: Boolean = false) =
        ItemActionsUiState.forMedia(item, pinned = false, extras = extras, dynamicQueue = dynamicQueue).actions

    private fun of(track: TrackUi, context: ActionsContext = ActionsContext.Library, dynamicQueue: Boolean = false) =
        ItemActionsUiState.forTrack(track, context, extras, dynamicQueue).actions

    @Test fun `a song, an album, an artist and a playlist offer their endless mix after the queue rows`() {
        val seeds = listOf(album, SampleData.artists[0], SampleData.playlists[0]).map { of(it) } + listOf(of(song))
        seeds.forEach { assertEquals(ItemAction.EndlessMix, it[it.indexOf(ItemAction.AddToUpNext) + 1]) }
        // A song on Home is a MediaUi, like the albums beside it.
        assertEquals(
            listOf(
                ItemAction.PlayNext,
                ItemAction.AddToUpNext,
                ItemAction.EndlessMix,
                ItemAction.AddToFavorites,
                ItemAction.SimilarSongs,
                ItemAction.PinToHome,
            ),
            of(MediaUi(song.key, MediaKind.Track, song.title)),
        )
    }

    @Test fun `a station, a book, a podcast and an episode have no mix and no similar songs`() {
        val others = listOf(
            MediaUi("library://radio/1", MediaKind.Radio, "Radio Lumen"),
            MediaUi("library://audiobook/3", MediaKind.Audiobook, "Moby-Dick"),
            MediaUi("library://podcast/2", MediaKind.Podcast, "Harbour Talk"),
            MediaUi("library://podcast_episode/x", MediaKind.Episode, "Why Bread Goes Stale"),
        )
        others.forEach { assertTrue(it.title, of(it).none(EXTRAS::contains)) }
    }

    @Test fun `a playlist the server fills anew, and a mix itself, have no mix of their own`() {
        val playlist = SampleData.playlists[0]
        assertTrue(ItemAction.EndlessMix in of(playlist))
        assertFalse(ItemAction.EndlessMix in of(playlist.copy(dynamic = true)))
        assertFalse(ItemAction.EndlessMix in of(playlist.copy(key = endlessMixUri(album.key))))
    }

    @Test fun `what only plays like a song has no mix and no similar songs`() {
        val sound = "ambient_sounds://sound_effect/1"
        assertTrue(of(song.copy(key = sound), ActionsContext.NowPlaying).none(EXTRAS::contains))
        assertTrue(of(MediaUi(sound, MediaKind.Track, "Rain")).none(EXTRAS::contains))
        // An Up next row the server names no item for is keyed by its queue item id.
        assertTrue(of(song.copy(key = "q7"), ActionsContext.UpNext).none(EXTRAS::contains))
    }

    @Test fun `each extra shows only where the server has it`() {
        fun with(has: ServerExtras) = ItemActionsUiState.forTrack(song, extras = has).actions.filter { it in EXTRAS }
        assertEquals(emptyList<ItemAction>(), with(ServerExtras()))
        assertEquals(listOf(ItemAction.EndlessMix), with(ServerExtras(mix = true)))
        assertEquals(listOf(ItemAction.SimilarSongs), with(ServerExtras(similarSongs = true)))
        assertTrue(ItemActionsUiState.forMedia(album, pinned = false).actions.none(EXTRAS::contains))
    }

    @Test fun `while a mix plays nothing is added to Up next, and only a song plays next`() {
        val queue = setOf(ItemAction.PlayNext, ItemAction.AddToUpNext)
        assertEquals(listOf(ItemAction.PlayNext), of(song, dynamicQueue = true).filter { it in queue })
        assertEquals(
            listOf(ItemAction.Play, ItemAction.Shuffle, ItemAction.EndlessMix),
            of(album, dynamicQueue = true).take(3),
        )
        val others = listOf(album, SampleData.artists[0], SampleData.playlists[0])
        others.forEach { assertTrue(it.title, of(it, dynamicQueue = true).none(queue::contains)) }
        val sound = song.copy(key = "ambient_sounds://sound_effect/1")
        assertTrue(of(sound, dynamicQueue = true).none(queue::contains))
        // In Up next, Play next moves the row: that works in a mix as anywhere.
        assertEquals(ItemAction.PlayNext, of(sound, ActionsContext.UpNext, dynamicQueue = true).first())
    }

    @Test fun `a book is marked finished, or unfinished once it is, and leads to its author`() {
        val book = MediaUi("library://audiobook/3", MediaKind.Audiobook, "Moby-Dick", "Herman Melville")
        assertEquals(
            listOf(
                ItemAction.MarkFinished,
                ItemAction.AddToFavorites,
                ItemAction.GoToAuthor,
                ItemAction.Bookmarks,
                ItemAction.PinToHome,
            ),
            ItemActionsUiState.forMedia(book.copy(artistKey = "library://artist/1"), pinned = false).actions,
        )
        val finished = ItemActionsUiState.forMedia(book.copy(fullyPlayed = true)).actions
        assertEquals(ItemAction.MarkUnfinished, finished.first())
        assertTrue(
            ItemAction.GoToAudiobook in ItemActionsUiState.forMedia(book, context = ActionsContext.NowPlaying).actions,
        )
    }

    @Test fun `an episode plays next, is marked played and leads to its podcast`() {
        val episode = MediaUi(
            "library://podcast_episode/x",
            MediaKind.Episode,
            "Why Bread Goes Stale",
            parentKey = "library://podcast/2",
        )
        assertEquals(
            listOf(
                ItemAction.PlayNext,
                ItemAction.AddToUpNext,
                ItemAction.MarkPlayed,
                ItemAction.GoToPodcast,
                ItemAction.Bookmarks,
            ),
            ItemActionsUiState.forMedia(episode).actions,
        )
        assertEquals(
            ItemAction.MarkUnplayed,
            ItemActionsUiState.forMedia(episode.copy(fullyPlayed = true, parentKey = null)).actions[2],
        )
    }

    @Test fun `a book or episode from Audiobookshelf downloads, stops on its way and is removed once there`() {
        val book = MediaUi("library://audiobook/3", MediaKind.Audiobook, "Das dicke Buch", downloadable = true)
        fun downloads(state: DownloadState?, item: MediaUi = book) = ItemActionsUiState.forMedia(item, download = state)
            .actions.filter { it in DOWNLOADS }
        assertEquals(listOf(ItemAction.Download), downloads(null))
        assertEquals(emptyList<ItemAction>(), downloads(null, book.copy(downloadable = false)))
        assertEquals(listOf(ItemAction.CancelDownload), downloads(DownloadState.Waiting()))
        assertEquals(listOf(ItemAction.CancelDownload), downloads(DownloadState.Running(1, 2)))
        assertEquals(listOf(ItemAction.RemoveDownload), downloads(DownloadState.Done))
        // A stopped download offers a retry or its removal, whatever the item's own flag says.
        assertEquals(
            listOf(ItemAction.Download, ItemAction.RemoveDownload),
            downloads(DownloadState.Failed(AbsException.Reason.Unreachable), book.copy(downloadable = false)),
        )
        val episode = MediaUi("library://podcast_episode/9", MediaKind.Episode, "Folge 1", downloadable = true)
        assertEquals(listOf(ItemAction.Download), downloads(null, episode))
        val album = MediaUi("library://album/1", MediaKind.Album, "Ein Album", downloadable = true)
        assertEquals(emptyList<ItemAction>(), downloads(null, album))
    }

    private val hosts = ServerExtras(djHosts = listOf(DjHost("music_nerd", "Music nerd")))

    // Now Playing's menu, from the same rules [ItemActionController.state] uses.
    private fun menu(playing: NowPlayingUi, extras: ServerExtras = hosts, serverQueue: Boolean = true) =
        when (val target = playing.moreTarget()) {
            is ActionTarget.Track -> {
                val dj = offersDj(target.track, extras, serverQueue)
                ItemActionsUiState.forTrack(target.track, target.context, extras, dj = dj).actions
            }

            is ActionTarget.Media ->
                ItemActionsUiState.forMedia(target.item, context = target.context, extras = extras).actions

            null -> emptyList()
        }

    @Test fun `Now Playing offers the DJ for a song as its third speaker row, where the server has hosts`() {
        val speaker = listOf(ItemAction.SleepTimer, ItemAction.TransferPlayback, ItemAction.Dj)
        assertEquals(speaker + ItemAction.PlayNext, menu(SampleData.nowPlaying).take(4))
        assertFalse(ItemAction.Dj in menu(SampleData.nowPlaying, extras))
        // Only Now Playing is about the speaker.
        assertFalse(ItemAction.Dj in ItemActionsUiState.forTrack(song, ActionsContext.UpNext, hosts).actions)
    }

    @Test fun `a book, an episode, a station and a download on This phone have no DJ`() {
        val book = PlayerSample.book
        val others = listOf(
            book,
            book.copy(kind = MediaKind.Episode, key = "library://podcast_episode/x"),
            SampleData.nowPlaying.copy(kind = MediaKind.Radio, key = "library://radio/1"),
            // A download playing from the phone's files, as LocalNowPlaying.kt shows it.
            book.copy(speaker = SampleData.speakers[0], canShuffle = false),
        )
        others.forEach { assertFalse(it.title, ItemAction.Dj in menu(it)) }
        // What plays from the phone's own files is on no server queue.
        assertFalse(ItemAction.Dj in menu(SampleData.nowPlaying, serverQueue = false))
        val sound = SampleData.nowPlaying.copy(key = "ambient_sounds://sound_effect/1")
        assertFalse(ItemAction.Dj in menu(sound))
    }

    @Test fun `what the DJ says can only be removed from Up next, and Now Playing keeps the speaker's rows`() {
        val words = TrackUi("ai_radio://sound_effect/dj_005", "Artist fact + Transition", "", djBreak = true)
        assertEquals(listOf(ItemAction.RemoveFromUpNext), of(words, ActionsContext.UpNext))
        assertEquals(emptyList<ItemAction>(), of(words))
        val playing = SampleData.nowPlaying.copy(key = words.key, title = words.title, artist = "", djBreak = true)
        val speaker = listOf(ItemAction.SleepTimer, ItemAction.TransferPlayback)
        // The DJ can be switched off while it talks; a guest, who sees no hosts, has the other two.
        assertEquals(speaker + ItemAction.Dj, menu(playing))
        assertEquals(speaker, menu(playing, extras))
        assertTrue(ItemActionsUiState.forTrack(words, ActionsContext.UpNext).djBreak)
        assertFalse(ItemActionsUiState.forTrack(song).djBreak)
    }

    private companion object {
        val EXTRAS = setOf(ItemAction.EndlessMix, ItemAction.SimilarSongs)
        val DOWNLOADS = setOf(ItemAction.Download, ItemAction.CancelDownload, ItemAction.RemoveDownload)
    }
}
