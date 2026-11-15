package io.github.hazymorning.amsel.ui.mapping

import io.github.hazymorning.amsel.api.Images
import io.github.hazymorning.amsel.api.MaJson
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.PlayerMedia
import io.github.hazymorning.amsel.api.PlayerQueue
import io.github.hazymorning.amsel.api.QueueItem
import io.github.hazymorning.amsel.api.ServerAddress
import io.github.hazymorning.amsel.api.decodeList
import io.github.hazymorning.amsel.api.fixture
import io.github.hazymorning.amsel.data.PositionAnchor
import io.github.hazymorning.amsel.data.QueueState
import io.github.hazymorning.amsel.data.home.HomeItem
import io.github.hazymorning.amsel.playback.snapshotOf
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Radio stations, books and podcasts as the recorded servers send them, in the UI's models. */
class UiMapperTest {
    private val address = ServerAddress.parse("http://ma.local:8095") ?: error("address")
    private val mapper = UiMapper { Images(address) }

    private fun result(version: String, name: String): JsonElement =
        fixture(version, name).getValue("response").jsonObject.getValue("result")

    @Test fun `a radio station is a radio with its artwork and no subtitle`() = listOf("2.10", "2.11").forEach { v ->
        val items = MaJson.decodeFromJsonElement(
            ListSerializer(MediaItem.serializer()),
            result(v, "music_radios_library_items"),
        )
        val station = mapper.media(items.single())
        assertEquals(MediaKind.Radio, station.kind)
        assertEquals("Amsel Test Radio", station.title)
        assertNull(station.subtitle)
        assertNotNull(station.art)
    }

    @Test fun `a playing radio station shows in Now Playing without a duration`() = listOf(
        "2.10",
        "2.11",
    ).forEach { v ->
        val queue = MaJson.decodeFromJsonElement(PlayerQueue.serializer(), result(v, "player_queues_get__radio"))
        val track = mapper.track(queue.currentItem?.mediaItem ?: error("no item"))
        assertEquals("Amsel Test Radio", track.title)
        assertEquals("", track.artist)
    }

    @Test fun `a book on Home shows where the person stands`() {
        val book =
            HomeItem("library://audiobook/2", "audiobook", "The Lantern Road", durationSec = 270, resumeMs = 90_000)
        val shown = mapper.media(book)
        assertEquals(1f / 3, shown.progress!!, 0.001f)
        assertEquals(180, shown.remainingSec)
        assertNull(mapper.media(book.copy(fullyPlayed = true)).progress)
        assertNull(mapper.media(book.copy(resumeMs = null)).remainingSec)
    }

    @Test fun `books, podcasts and episodes are their own kinds, never albums`() = listOf("2.10", "2.11").forEach { v ->
        fun items(name: String) = MaJson.decodeFromJsonElement(ListSerializer(MediaItem.serializer()), result(v, name))
        val book = mapper.media(items("music_audiobooks_library_items").first())
        assertEquals(MediaKind.Audiobook, book.kind)
        assertEquals("Ada Quill", book.subtitle)
        assertEquals(MediaKind.Podcast, mapper.media(items("music_podcasts_library_items").single()).kind)
        val episode = mapper.media(items("music_podcasts_podcast_episodes").first())
        assertEquals(MediaKind.Episode, episode.kind)
        assertEquals("Harbour Talk", episode.subtitle)
        assertEquals(MediaKind.Audiobook, mapper.media(items("music_in_progress_items").single()).kind)
    }

    private fun queue(version: String, name: String): PlayerQueue =
        MaJson.decodeFromJsonElement(PlayerQueue.serializer(), result(version, name))

    private val speaker = SpeakerUi("player", "Kitchen")

    private fun snapshot(queue: PlayerQueue, player: Player = Player("player")) =
        snapshotOf(player, QueueState(queue, PositionAnchor(0.0, 0)), null, false, null, nowMs = 0)

    private fun nowPlaying(queue: PlayerQueue, player: Player = Player("player")) =
        mapper.nowPlaying(player, queue, snapshot(queue, player), speaker) ?: error("nothing playing")

    @Test fun `Now Playing knows which song or station plays, for its sheet`() = listOf("2.10", "2.11").forEach { v ->
        val song = queue(v, "player_queues_get__playing")
        val playing = nowPlaying(song)
        assertEquals(MediaKind.Track, playing.kind)
        assertEquals(song.currentItem?.mediaItem?.uri, playing.key)

        val radio = queue(v, "player_queues_get__radio")
        val station = nowPlaying(radio)
        assertEquals(MediaKind.Radio, station.kind)
        assertEquals("library://radio/1", station.key)
        assertEquals(radio.currentItem?.mediaItem?.favorite == true, station.favorite)
    }

    @Test fun `Now Playing names what an endless mix was made from, and no mix otherwise`() = listOf(
        "2.10",
        "2.11",
    ).forEach { v ->
        val mix = queue(v, "player_queues_get__endless_mix")
        assertEquals("First Light", nowPlaying(mix).mix)
        // A skip shows the next song at once, in the same mix.
        val skipping = mix.copy(nextItem = mix.currentItem)
        assertEquals("First Light", mapper.nowPlayingNext(skipping, snapshot(skipping), speaker)?.mix)
        assertNull(nowPlaying(queue(v, "player_queues_get__playing")).mix)
        assertNull(nowPlaying(queue(v, "player_queues_get__radio")).mix)
        // One of the server's own "Infinite Mix" playlists fills a queue as well, and is no endless mix.
        assertNull(nowPlaying(mix.copy(sources = emptyList())).mix)
        // AirPlay has the speaker: what it plays is not the mix its queue still holds.
        val airplay = Player("player", activeSource = "airplay", currentMedia = PlayerMedia(title = "Elsewhere"))
        assertNull(nowPlaying(mix.copy(active = false), airplay).mix)
    }

    @Test fun `what the DJ says between two songs is told from a song, in Up next and in Now Playing`() {
        val queue = queue("2.11", "player_queues_get__dj_break")
        val item = queue.currentItem ?: error("no item")
        val words = mapper.queueItem(item, item.queueItemId)
        assertTrue(words.track.djBreak)
        assertTrue(words.isCurrent)
        assertEquals("Artist fact + Transition" to "", words.track.title to words.track.artist)
        assertNotNull(words.track.art)
        assertFalse(mapper.queueItem(queue.nextItem ?: error("no next"), item.queueItemId).track.djBreak)

        val playing = nowPlaying(queue)
        assertTrue(playing.djBreak)
        assertEquals("Artist fact + Transition", playing.title)
        assertEquals("ai_radio://sound_effect/djbdee4d328816_005", playing.key)
        assertNull(playing.heart(canWrite = true))
        // A skip shows the song that follows, which is a song again.
        assertEquals(false, mapper.nowPlayingNext(queue, snapshot(queue), speaker)?.djBreak)
        // Another plugin's sound is not the DJ's.
        val rain = item.copy(mediaItem = item.mediaItem?.copy(provider = "ambient_sounds"))
        assertFalse(mapper.queueItem(rain, null).track.djBreak)
        assertFalse(nowPlaying(queue.copy(currentItem = rain)).djBreak)
        listOf("2.10", "2.11").forEach { v -> assertFalse(nowPlaying(queue(v, "player_queues_get__playing")).djBreak) }
    }

    @Test fun `shuffle and repeat are for a queue of songs alone`() = listOf("2.10", "2.11").forEach { v ->
        val songs = queue(v, "player_queues_get__playing")
        assertTrue(nowPlaying(songs).canShuffle)
        // A station is one stream, and a mix keeps its own order.
        assertFalse(nowPlaying(queue(v, "player_queues_get__radio")).canShuffle)
        assertFalse(nowPlaying(queue(v, "player_queues_get__endless_mix")).canShuffle)
        // AirPlay has the speaker: the queue's order is not what plays.
        val airplay = Player("player", activeSource = "airplay", currentMedia = PlayerMedia(title = "Elsewhere"))
        assertFalse(nowPlaying(songs.copy(active = false), airplay).canShuffle)
    }

    @Test fun `2_10 marks what the DJ says the same way`() {
        val item = decodeList("2.10", "player_queues_items__dj_break", QueueItem.serializer()).single()
        val words = mapper.queueItem(item, null)
        assertTrue(words.track.djBreak)
        assertEquals("Artist fact", words.track.title)
    }

    @Test fun `a single names no album, an album does`() {
        assertNull(albumBesides("Lanterns", "Lanterns"))
        assertNull(albumBesides("Lanterns", "lanterns - Single"))
        assertNull(albumBesides("Lanterns", "Lanterns (EP)"))
        assertEquals("Blue Hour", albumBesides("Lanterns", "Blue Hour"))
        assertEquals("Lanterns at Dusk", albumBesides("Lanterns", "Lanterns at Dusk"))
        assertNull(albumBesides("Lanterns", " "))
    }

    @Test fun `a station names the song on air and itself under it`() = listOf("2.10", "2.11").forEach { v ->
        val player = MaJson.decodeFromJsonElement(Player.serializer(), result(v, "players_get__radio"))
        val station = NowPlayingUi(
            title = "Amsel Test Radio",
            artist = "",
            positionAtMs = 0,
            isPlaying = true,
            speaker = SpeakerUi("kitchen", "Kitchen"),
            kind = MediaKind.Radio,
        )
        val media = player.currentMedia ?: PlayerMedia()
        val onAir = station.onAir(player.copy(currentMedia = media.copy(title = "Lanterns", artist = "Marlene Vogt")))
        assertEquals("Lanterns", onAir.title)
        assertEquals("Marlene Vogt · Amsel Test Radio", onAir.artist)
        // Without a song of its own, the station keeps its name.
        assertEquals(station, station.onAir(player.copy(currentMedia = media.copy(title = "Amsel Test Radio"))))
    }
}
