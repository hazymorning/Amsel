package io.github.hazymorning.amsel.api

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenWordDecodingTest {
    private val versions = listOf("2.10", "2.11")

    @Test fun `audiobooks with authors as artists or as plain names`() = versions.forEach { v ->
        val books = decodeList(v, "music_audiobooks_library_items", MediaItem.serializer())
        assertEquals(listOf("The Lantern Road", "Single File Tale"), books.map { it.name })
        assertTrue(books.all { it.mediaType == MediaType.Audiobook.api && it.duration!! > 0 })
        assertTrue(books.all { it.subtitle == "Ada Quill" })
        val authorUri = books.first().authors.single().uri
        if (v == "2.11") assertEquals("library://artist/1", authorUri) else assertNull(authorUri)
        val inProgress = decodeList(v, "music_audiobooks_library_items__in_progress", MediaItem.serializer())
        assertEquals(false, inProgress.first().fullyPlayed)
        assertTrue(inProgress.first().resumePositionMs!! > 100_000)
    }

    @Test fun `chapters are 1-based from files and 0-based from one file`() = versions.forEach { v ->
        val book = decodeOne(v, "music_audiobooks_get", MediaItem.serializer())
        assertEquals(listOf(1, 2, 3), book.chapters.map { it.position })
        assertEquals(180.0, book.chapters.last().start, 0.2)
        val single = decodeOne(v, "music_audiobooks_get__single_file", MediaItem.serializer())
        assertEquals(listOf(0, 1, 2), single.chapters.map { it.position })
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), book.chapters.map { it.name })
    }

    @Test fun `podcasts and their episodes`() = versions.forEach { v ->
        val podcast = decodeList(v, "music_podcasts_library_items", MediaItem.serializer()).single()
        assertEquals(MediaType.Podcast.api, podcast.mediaType)
        val episodes = decodeList(v, "music_podcasts_podcast_episodes", MediaItem.serializer())
        assertEquals(3, episodes.size)
        assertTrue(episodes.all { it.mediaType == MediaType.PodcastEpisode.api && it.subtitle == "Harbour Talk" })
        assertTrue(episodes.all { it.podcast?.uri?.endsWith("://podcast/Harbour Talk") == true })
        val played = decodeList(v, "music_podcasts_podcast_episodes__in_progress", MediaItem.serializer())
        assertEquals(43_000L, played.first { it.resumePositionMs != null }.resumePositionMs)
    }

    @Test fun `in progress lists a book, then an episode`() = versions.forEach { v ->
        val book = decodeList(v, "music_in_progress_items", MediaItem.serializer()).single()
        assertEquals("library://audiobook/2", book.uri)
        val episode = decodeList(v, "music_in_progress_items__episode", MediaItem.serializer()).single()
        assertEquals(MediaType.PodcastEpisode.api, episode.mediaType)
    }

    @Test fun `a playing book carries its chapters and speed`() = versions.forEach { v ->
        val queue = decodeOne(v, "player_queues_get__audiobook", PlayerQueue.serializer())
        assertEquals(1.5, queue.playbackSpeed, 0.0)
        val book = queue.currentItem?.mediaItem
        assertNotNull(book)
        assertEquals(3, book!!.chapters.size)
        assertTrue(book.isSpokenWord)
        val episode = decodeOne(v, "player_queues_get__episode", PlayerQueue.serializer())
        assertEquals(MediaType.PodcastEpisode.api, episode.currentItem?.mediaItem?.mediaType)
    }

    @Test fun `an Audiobookshelf episode's publish time as its position`() {
        val episode = MaJson.decodeFromString(MediaItem.serializer(), ABS_EPISODE)
        assertEquals(1_730_530_800_000L, episode.position)
        assertEquals("Harbour Talk", episode.subtitle)
        val list = MaJson.decodeFromString(ListSerializer(MediaItem.serializer()), "[$ABS_EPISODE, $ABS_EPISODE]")
        assertEquals(2, list.size)
    }

    @Test fun `a queue playing an Audiobookshelf episode is kept`() {
        val queue = MaJson.decodeFromString(PlayerQueue.serializer(), queueWith(ABS_EPISODE))
        assertEquals("playing", queue.state)
        assertEquals(1_730_530_800_000L, queue.currentItem?.mediaItem?.position)
        assertEquals(600_000L, queue.currentItem?.mediaItem?.resumePositionMs)
    }

    @Test fun `an item that does not decode costs only itself`() {
        val broken = """{"item_id": "x", "provider": "p", "name": {"text": "?"}, "uri": "p://track/x"}"""
        val queue = MaJson.decodeFromString(PlayerQueue.serializer(), queueWith(broken))
        assertEquals("q", queue.queueId)
        assertEquals("x1", queue.currentItem?.queueItemId)
        assertNull(queue.currentItem?.mediaItem)
    }

    @Test fun `a search's books and podcasts, a broken one left out`() = versions.forEach { v ->
        val book = fixture(v, "music_audiobooks_library_items")["response"]!!.jsonObject["result"] as JsonArray
        val podcast = fixture(v, "music_podcasts_library_items")["response"]!!.jsonObject["result"] as JsonArray
        val broken = """{"item_id": "x", "provider": "p", "name": {"text": "?"}, "uri": "p://audiobook/x"}"""
        val results = MaJson.decodeFromString(
            SearchResults.serializer(),
            """{"artists": [], "audiobooks": [${book.first()}, $broken], "podcasts": $podcast}""",
        )
        assertEquals(listOf("The Lantern Road"), results.audiobooks.map { it.name })
        assertEquals(listOf("Harbour Talk"), results.podcasts.map { it.name })
    }

    @Test fun `numbers in other shapes`() {
        val item = MaJson.decodeFromString(
            MediaItem.serializer(),
            """{"item_id": "1", "provider": "p", "name": "n", "uri": "p://audiobook/1", "media_type": "audiobook",
               "duration": 1805.6, "position": "12", "episode_number": "x", "year": 3000000000,
               "authors": ["Ada", {"name": "Ben", "uri": "library://artist/2"}, {"no": "name"}, 7]}""",
        )
        assertEquals(1805, item.duration)
        assertEquals(12L, item.position)
        assertNull(item.year)
        assertEquals(listOf("Ada", "Ben"), item.authors.map { it.name })
        val kept = MaJson.decodeFromString(MediaItem.serializer(), MaJson.encodeToString(MediaItem.serializer(), item))
        assertEquals(item.authors, kept.authors)
    }

    @Test fun `a chapter list merged after an edit keeps only the current chapters`() {
        fun book(chapters: String) = MaJson.decodeFromString(
            MediaItem.serializer(),
            """{"item_id": "1", "provider": "library", "name": "n", "uri": "library://audiobook/1",
               "media_type": "audiobook", "duration": 270, "metadata": {"chapters": [$chapters]}}""",
        )
        fun chapter(position: Int?, start: Int, end: Int) =
            """{"position": $position, "name": "c", "start": $start.0, "end": $end.0}"""
        // The server's merge after a chapter edit in Audiobookshelf. See docs/API-NOTES.md, "Audiobooks and podcasts".
        val edited = book(
            listOf(chapter(1, 0, 90), chapter(2, 90, 180), chapter(3, 180, 270))
                .plus((1..270).map { chapter(it, it - 1, it) })
                .joinToString(),
        )
        assertEquals((1..270).toList(), edited.chapters.map { it.position })
        // A chapter both lists hold stays only in the current one.
        val kept = book(listOf(chapter(2, 90, 180), chapter(1, 0, 90), chapter(2, 90, 270)).joinToString())
        assertEquals(listOf(0.0, 90.0), kept.chapters.map { it.start })
        // One chapter renamed: only that one is new, the others stay.
        val ten = (1..10).map { chapter(it, (it - 1) * 27, it * 27) }
        val renamed =
            book((ten + """{"position": 5, "name": "Renamed", "start": 108.0, "end": 135.0}""").joinToString())
        assertEquals((1..10).toList(), renamed.chapters.map { it.position })
        assertEquals("Renamed", renamed.chapters[4].name)
        // A shorter list: the stored third chapter overlaps the new second one and goes.
        val shorter = book(
            listOf(
                chapter(1, 0, 90),
                chapter(2, 90, 180),
                chapter(3, 180, 270),
                chapter(1, 0, 135),
                chapter(2, 135, 270),
            )
                .joinToString(),
        )
        assertEquals(listOf(0.0, 135.0), shorter.chapters.map { it.start })
        val single = book(listOf(chapter(0, 0, 40), chapter(1, 40, 80), chapter(2, 80, 120)).joinToString())
        assertEquals(3, single.chapters.size)
        val unnumbered = book(listOf(chapter(null, 0, 40), chapter(null, 40, 120)).joinToString())
        assertEquals(2, unnumbered.chapters.size)
    }

    @Test fun `a chapter starts the book with a position the server takes for that chapter`() {
        fun book(chapters: String) = MaJson.decodeFromString(
            MediaItem.serializer(),
            """{"item_id": "1", "provider": "library", "name": "n", "uri": "library://audiobook/1",
               "media_type": "audiobook", "duration": 1200, "metadata": {"chapters": [$chapters]}}""",
        )
        fun chapter(position: Int, start: Int, end: Int = start + 40) =
            """{"position": $position, "name": "Kapitel", "start": $start.0, "end": $end.0}"""
        // A book's chapters from local files (0-based) merged with Audiobookshelf's (1-based).
        // See docs/API-NOTES.md, "start_item".
        val merged =
            book(((0..29).map { chapter(it, it * 40) } + (1..30).map { chapter(it, (it - 1) * 40) }).joinToString())
        val chapters = merged.chapters
        assertEquals((0..29).map { it * 40.0 }, chapters.map { it.start })
        // Chapter 25 is Audiobookshelf's 25th; the server's first 25 is the files' chapter 26.
        assertEquals(960.0, chapters[24].start, 0.0)
        assertEquals("24", merged.startItemFor(chapters[24]))
        assertEquals("0", merged.startItemFor(chapters[0]))
        // After an edit in Audiobookshelf the stale chapter 2 starts at 1:30, the current one at 0:01.
        val edited = book(
            listOf(chapter(1, 0, 90), chapter(2, 90, 180), chapter(3, 180, 270))
                .plus((1..30).map { chapter(it, it - 1, it) })
                .joinToString(),
        )
        assertEquals(null, edited.startItemFor(edited.chapters[1]))
        assertEquals("10", edited.startItemFor(edited.chapters[9]))
        val plain = book((1..3).map { chapter(it, (it - 1) * 40) }.joinToString())
        assertEquals(listOf("1", "2", "3"), plain.chapters.map { plain.startItemFor(it) })
    }

    private fun queueWith(item: String) = """
        {"queue_id": "q", "state": "playing", "elapsed_time": 12.5, "playback_speed": 1.25,
         "current_item": {"queue_id": "q", "queue_item_id": "x1", "name": "Episode", "duration": 1805,
                          "media_item": $item}}
    """.trimIndent()

    private companion object {
        /** Shaped as `providers/audiobookshelf/parsers.py` builds an episode. */
        val ABS_EPISODE = """
            {"item_id": "li_7 ep_12", "provider": "audiobookshelf--a1", "name": "Fog Signals",
             "version": "", "uri": "audiobookshelf--a1://podcast_episode/li_7 ep_12",
             "media_type": "podcast_episode", "is_playable": true,
             "metadata": {"description": "<p>Fog</p>", "release_date": "2024-11-02T07:00:00+00:00",
                          "images": [{"type": "thumb", "path": "http://abs/api/items/li_7/cover",
                                      "provider": "audiobookshelf--a1", "remotely_accessible": true,
                                      "proxy_id": "abc"}], "chapters": []},
             "position": 1730530800000,
             "podcast": {"item_id": "li_7", "provider": "audiobookshelf--a1", "name": "Harbour Talk",
                         "version": "", "uri": "audiobookshelf--a1://podcast/li_7", "media_type": "podcast",
                         "available": true, "image": null},
             "duration": 1805, "episode_number": 12, "season": null,
             "fully_played": false, "resume_position_ms": 600000}
        """.trimIndent()
    }
}
