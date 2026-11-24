package io.github.hazymorning.amsel.sample

import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.ChapterUi
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.screens.pages.AudiobookUi
import io.github.hazymorning.amsel.ui.screens.pages.AudiobookUiState
import io.github.hazymorning.amsel.ui.screens.pages.EpisodeUi
import io.github.hazymorning.amsel.ui.screens.pages.PodcastUiState
import java.time.LocalDate

object SpokenSample {
    private fun art(key: String) = ArtRef("sample:$key")

    private const val HOUR = 3600

    fun book(n: Int, title: String, author: String, progress: Float? = null, remainingSec: Int? = null) = MediaUi(
        "library://audiobook/$n",
        MediaKind.Audiobook,
        title,
        author,
        art("audiobook/$n"),
        progress = progress,
        remainingSec = remainingSec,
    )

    val mobyDick = book(3, "Moby-Dick", "Herman Melville")
    val annaKarenina = book(4, "Anna Karenina", "Leo Tolstoy")

    fun podcast(n: Int, title: String, publisher: String) =
        MediaUi("library://podcast/$n", MediaKind.Podcast, title, publisher, art("podcast/$n"))

    val longWalk = podcast(1, "The Long Walk", "Footpath Audio")
    val kitchenScience = podcast(2, "Kitchen Science", "Weekly")

    private val mobyDickChapters = listOf(
        "Loomings" to 705, "The Carpet-Bag" to 612, "The Spouter-Inn" to 1338, "The Counterpane" to 812,
        "Breakfast" to 431, "The Street" to 395, "The Chapel" to 489, "The Pulpit" to 371, "The Sermon" to 1544,
        "A Bosom Friend" to 588, "Nightgown" to 330, "Biographical" to 402, "Wheelbarrow" to 598,
        "Nantucket" to 351, "Chowder" to 486, "The Ship" to 1902,
    )

    val chapters: List<ChapterUi> = mobyDickChapters.runningFold(0L) { start, (_, sec) -> start + sec * 1000L }
        .zip(mobyDickChapters)
        .mapIndexed { i, (startMs, chapter) ->
            ChapterUi(i + 1, chapter.first, startMs, startMs + chapter.second * 1000L, (i + 1).toString())
        }

    private const val MOBY_DICK_SEC = 24 * HOUR + 49 * 60

    val mobyDickAtMs: Long = chapters[2].startMs + 254_000

    val audiobookPage = AudiobookUiState(
        book = AudiobookUi(
            key = mobyDick.key,
            title = mobyDick.title,
            art = mobyDick.art,
            author = "Herman Melville",
            authorKey = "library://artist/31",
            narrator = "Thomas Reed",
            durationSec = MOBY_DICK_SEC,
            resumeMs = mobyDickAtMs,
        ),
        chapters = chapters,
    )

    private fun episode(
        n: Int,
        title: String,
        date: LocalDate,
        minutes: Int,
        progress: Float? = null,
        played: Boolean = false,
    ) = EpisodeUi(
        key = "library://podcast_episode/long-walk-$n",
        title = title,
        date = date,
        durationSec = minutes * 60,
        resumeMs = progress?.let { (minutes * 60_000 * it).toLong() },
        remainingSec = progress?.let { (minutes * 60 * (1 - it)).toInt() },
        played = played,
        art = longWalk.art,
        podcast = longWalk.title,
    )

    val episodes = listOf(
        episode(52, "The Thames Path, Part Two", LocalDate.of(2025, 10, 28), 52, progress = 0.35f),
        episode(51, "Night Walks in Lisbon", LocalDate.of(2025, 10, 21), 47, progress = 0.6f),
        episode(50, "Rain on the West Highland Way", LocalDate.of(2025, 10, 14), 58),
        episode(49, "The Art of Getting Lost", LocalDate.of(2025, 10, 7), 41, played = true),
        episode(48, "Walking the Camino Primitivo", LocalDate.of(2025, 9, 30), 63, played = true),
        episode(47, "A Walk with No Destination", LocalDate.of(2025, 9, 23), 36),
        episode(46, "Winter Light on the South Downs", LocalDate.of(2025, 9, 16), 44, played = true),
        episode(45, "Ten Thousand Steps, Revisited", LocalDate.of(2025, 9, 9), 39, played = true),
    )

    val podcastPage = PodcastUiState(
        podcast = longWalk,
        episodes = episodes,
        currentKey = episodes.first().key,
    )

    val continueListening = listOf(
        mobyDick.copy(
            progress = mobyDickAtMs / 1000f / MOBY_DICK_SEC,
            remainingSec =
                MOBY_DICK_SEC - (mobyDickAtMs / 1000).toInt(),
        ),
        annaKarenina.copy(progress = 0.62f, remainingSec = 13 * HOUR + 38 * 60),
        MediaUi(
            "library://podcast_episode/kitchen-science-47",
            MediaKind.Episode,
            "Why Bread Goes Stale",
            kitchenScience.title,
            art("podcast/2"),
            progress = 0.4f,
            remainingSec = 23 * 60,
            parentKey = kitchenScience.key,
        ),
    )
}
