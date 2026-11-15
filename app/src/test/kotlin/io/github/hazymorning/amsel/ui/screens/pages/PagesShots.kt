package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import io.github.hazymorning.amsel.data.downloads.DownloadState
import io.github.hazymorning.amsel.sample.PagesSample
import io.github.hazymorning.amsel.sample.SpokenSample
import io.github.hazymorning.amsel.testing.ScreenshotTest
import io.github.hazymorning.amsel.testing.ShotVariant
import org.junit.Test

class PagesShots : ScreenshotTest() {
    @Test fun album() = snap("album", ShotVariant.entries) { Album(PagesSample.album) }

    @Test fun albumError() = snap("album_error") { Album(PagesSample.albumUnavailable) }

    @Test fun playlist() = snap("playlist") { Playlist(PagesSample.playlist) }

    @Test fun artist() = snap("artist") { Artist(PagesSample.artist) }

    /** The page's end: the last albums, then the shelf of similar artists. */
    @Test fun artistSimilar() = snap("artist_similar", ShotVariant.entries, before = { toTheEnd() }) {
        Artist(PagesSample.artist)
    }

    @Test fun similarSongs() = snap("similar_songs", ShotVariant.entries) { SimilarSongs(PagesSample.similarSongs) }

    @Test fun similarSongsEmpty() = snap("similar_songs_empty") { SimilarSongs(SimilarSongsUiState()) }

    @Test fun audiobook() = snap("audiobook", listOf(ShotVariant.Light, ShotVariant.Dark)) {
        AudiobookScreen(
            SpokenSample.audiobookPage,
            onBack = {},
            onResume = {},
            onPlayChapter = {},
            onMore = {},
            onOpenAuthor = {},
            onRetry = {},
        )
    }

    /** A book from Audiobookshelf on its way to the phone: the ring beside play fills, its stop ends it. */
    @Test fun audiobookDownloading() = snap("audiobook_downloading", ShotVariant.entries) {
        val page = SpokenSample.audiobookPage
        AudiobookScreen(
            page.copy(book = page.book?.copy(downloadable = true), download = DownloadState.Running(41, 100)),
            onBack = {},
            onResume = {},
            onPlayChapter = {},
            onMore = {},
            onOpenAuthor = {},
            onRetry = {},
        )
    }

    /** One episode on the phone, the next on its way: each says so first in its line. */
    @Test fun podcastDownloads() = snap("podcast_downloads") {
        val episodes = SpokenSample.podcastPage.episodes
        PodcastScreen(
            SpokenSample.podcastPage,
            onBack = {},
            onPlayEpisode = {},
            onMore = {},
            onMoreEpisode = {},
            onLoadMore = {},
            onRetry = {},
            downloads = mapOf(episodes[0].key to DownloadState.Done, episodes[1].key to DownloadState.Running(62, 100)),
        )
    }

    @Test fun podcast() = snap("podcast") {
        PodcastScreen(
            SpokenSample.podcastPage,
            onBack = {},
            onPlayEpisode = {},
            onMore = {},
            onMoreEpisode = {},
            onLoadMore = {},
            onRetry = {},
        )
    }

    private fun SemanticsNodeInteractionsProvider.toTheEnd() = repeat(8) {
        onRoot().performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.2f, durationMillis = 400) }
    }
}

@Composable
private fun SimilarSongs(state: SimilarSongsUiState) = SimilarSongsScreen(
    state,
    title = "Lanterns",
    onBack = {},
    onPlayTrack = {},
    onMoreTrack = {},
    onRetry = {},
)

@Composable
private fun Album(state: AlbumUiState) = AlbumBody(state)

@Composable
private fun AlbumBody(state: AlbumUiState) = AlbumScreen(
    state,
    onBack = {},
    onPlay = {},
    onShuffle = {},
    onPlayTrack = {},
    onMore = {},
    onMoreTrack = {},
    onOpen = {},
    onOpenArtist = {},
    onRetry = {},
)

@Composable
private fun Playlist(state: PlaylistUiState) = PlaylistBody(state)

@Composable
private fun PlaylistBody(state: PlaylistUiState) = PlaylistScreen(
    state,
    onBack = {},
    onPlay = {},
    onShuffle = {},
    onPlayTrack = { _, _ -> },
    onMore = {},
    onMoreTrack = { _, _ -> },
    onRetry = {},
)

@Composable
private fun Artist(state: ArtistUiState) = ArtistBody(state)

@Composable
private fun ArtistBody(state: ArtistUiState) = ArtistScreen(
    state,
    onBack = {},
    onPlay = {},
    onShuffle = {},
    onPlayTrack = {},
    onMore = {},
    onMoreTrack = {},
    onOpen = {},
    onRetry = {},
)
