package io.github.hazymorning.amsel.ui.screens.pages

import io.github.hazymorning.amsel.api.EPISODE_PAGE
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.app.ItemDownloadState
import io.github.hazymorning.amsel.app.MessageController
import io.github.hazymorning.amsel.data.downloads.DownloadChapter
import io.github.hazymorning.amsel.data.downloads.DownloadManifest
import io.github.hazymorning.amsel.data.downloads.DownloadState
import io.github.hazymorning.amsel.data.library.ProgressUpdate
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Fixtures are recorded 2.11 server replies.
@OptIn(ExperimentalCoroutinesApi::class)
class SpokenPagesViewModelTest {
    private val messages = MessageController(MutableStateFlow(true))
    private val mapper = UiMapper { null }
    private val playing = MutableStateFlow<NowPlayingUi?>(null)
    private val playingUri = MutableStateFlow<String?>(null)

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    private fun bookRow(resumeMs: Long?) = row(BOOK, "audiobook", "The Lantern Road", subtitle = "Ada Quill")
        .copy(durationSec = 270, resumeMs = resumeMs, fullyPlayed = false, narrators = "Ben Reader")

    @Test fun `a book paints from its row, then shows its chapters and resumes where the person stopped`() = runTest {
        val source = FakePageSource(gated = true).apply {
            rows.value = bookRow(resumeMs = 100_000)
            item = one("music_audiobooks_get")
        }
        val state = watch(AudiobookViewModel(BOOK, source, mapper, playing).state)

        val cached = state.value
        assertEquals("The Lantern Road", cached.book?.title)
        assertEquals("Ben Reader", cached.book?.narrator)
        assertTrue(cached.chaptersLoading)
        assertTrue(cached.started)

        source.open()
        advanceUntilIdle()
        val loaded = state.value
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), loaded.chapters.map { it.title })
        assertEquals(listOf("1", "2", "3"), loaded.chapters.map { it.position })
        assertEquals(90_000L, loaded.chapters[1].startMs)
        // The row's progress wins over the copy the server sent: events keep the row current.
        assertEquals(100_000L, loaded.book?.resumeMs)
        assertEquals(1, loaded.chapterAt(nowMs = 0))
    }

    @Test fun `a downloaded book shows from its download without a connection, and plays`() = runTest {
        val source = FakePageSource().apply { failure = MaConnectionException("offline") }
        val manifest = DownloadManifest(
            uri = BOOK,
            absItemId = "book1",
            absEpisodeId = null,
            title = "Das dicke Buch",
            author = "Testautorin",
            mediaType = "audiobook",
            durationSec = 200.0,
            chapters = listOf(DownloadChapter("Eins", 0.0, 150.0), DownloadChapter("Zwei", 150.0, 0.0)),
            complete = true,
        )
        val download = MutableStateFlow(ItemDownloadState(DownloadState.Done, manifest, File("/data/cover")))
        val state = watch(AudiobookViewModel(BOOK, source, mapper, playing, download).state)
        advanceUntilIdle()

        val shown = state.value
        assertFalse(shown.showUnavailable)
        assertEquals("Das dicke Buch", shown.book?.title)
        assertEquals("file:///data/cover", shown.book?.art?.base)
        assertEquals(listOf("Eins", "Zwei"), shown.chapters.map { it.title })
        // A chapter without its end runs to the book's.
        assertEquals(200_000L, shown.chapters[1].endMs)
        assertTrue(shown.offersDownload)
        download.value = ItemDownloadState()
        assertTrue(state.value.showUnavailable)
    }

    @Test fun `a book on the speaker follows the speaker, and a mark shows at once`() = runTest {
        val source = FakePageSource(gated = true).apply {
            rows.value = bookRow(resumeMs = 10_000)
            item = one("music_audiobooks_get")
            open()
        }
        val state = watch(AudiobookViewModel(BOOK, source, mapper, playing).state)
        playing.value = NowPlayingUi(
            title = "The Lantern Road",
            artist = "Ada Quill",
            positionMs = 200_000,
            positionAtMs = 1_000,
            isPlaying = true,
            speaker = SpeakerUi("kitchen", "Kitchen"),
            key = BOOK,
            speed = 1.5f,
        )
        advanceUntilIdle()
        assertEquals(203_000L, state.value.positionAt(nowMs = 3_000))
        assertEquals(2, state.value.chapterAt(nowMs = 3_000))

        playing.value = null
        source.rows.value = bookRow(resumeMs = null).copy(fullyPlayed = true)
        advanceUntilIdle()
        assertEquals(true, state.value.book?.fullyPlayed)
        assertFalse(state.value.started)
        assertNull(state.value.positionAt(nowMs = 3_000))
    }

    @Test fun `a podcast the server cannot name, and the index lacks, offers Retry`() = runTest {
        val source = FakePageSource().apply { pages = listOf(list("music_podcasts_podcast_episodes__page")) }
        val state = watch(PodcastViewModel(PODCAST, source, mapper, playingUri, messages).state)
        advanceUntilIdle()
        assertTrue(state.value.episodes.isNotEmpty())
        assertTrue(state.value.showUnavailable)
    }

    @Test fun `a podcast lists its newest episodes, then more as the list nears its end`() = runTest {
        val first = list("music_podcasts_podcast_episodes__page")
        val source = FakePageSource(gated = true).apply {
            item = one("music_podcasts_get")
            pages = listOf(List(EPISODE_PAGE) { i -> first[i % 2].copy(uri = "${first[i % 2].uri}#$i") }, first)
            open()
        }
        val vm = PodcastViewModel(PODCAST, source, mapper, playingUri, messages)
        val state = watch(vm.state)
        advanceUntilIdle()
        assertEquals("Harbour Talk", state.value.podcast?.title)
        assertEquals(EPISODE_PAGE, state.value.episodes.size)
        assertFalse(state.value.complete)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(0, EPISODE_PAGE), source.asked)
        assertEquals(EPISODE_PAGE + 2, state.value.episodes.size)
        assertTrue(state.value.complete)
        vm.loadMore()
        assertEquals(2, source.asked.size)
    }

    @Test fun `an episode the feed repeats lists once`() = runTest {
        val episodes = list("music_podcasts_podcast_episodes__page")
        val source = FakePageSource().apply {
            item = one("music_podcasts_get")
            pages = listOf(episodes + episodes.first())
        }
        val state = watch(PodcastViewModel(PODCAST, source, mapper, playingUri, messages).state)
        advanceUntilIdle()
        assertEquals(episodes.map { it.uri }, state.value.episodes.map { it.key })
    }

    @Test fun `an episode follows the person's progress and the speaker`() = runTest {
        val episodes = list("music_podcasts_podcast_episodes__page")
        val source = FakePageSource(gated = true).apply {
            item = one("music_podcasts_get")
            pages = listOf(episodes)
            open()
        }
        val state = watch(PodcastViewModel(PODCAST, source, mapper, playingUri, messages).state)
        advanceUntilIdle()
        val newest = episodes.first().uri
        source.progress.emit(ProgressUpdate(newest, fullyPlayed = false, positionMs = 45_000))
        val moved = state.value.episodes.first()
        assertEquals(45_000L, moved.resumeMs)
        assertEquals(30, moved.remainingSec)
        source.progress.emit(ProgressUpdate(newest, fullyPlayed = true, positionMs = 0))
        assertTrue(state.value.episodes.first().played)
        assertNull(state.value.episodes.first().remainingSec)

        playingUri.value = newest
        advanceUntilIdle()
        assertEquals(newest, state.value.currentKey)
    }

    @Test fun `episode dates read from the server's release date`() {
        val episode = mapper.episode(list("music_podcasts_podcast_episodes__page").first())
        assertEquals(java.time.LocalDate.of(2026, 10, 3), episode.date)
        assertEquals(java.time.LocalDate.of(2024, 11, 2), dayOf("2024-11-02"))
        assertNull(dayOf("soon"))
    }

    private companion object {
        const val BOOK = "library://audiobook/2"
        const val PODCAST = "library://podcast/1"
    }
}
