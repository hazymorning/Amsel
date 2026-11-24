package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.PlayerMedia
import io.github.hazymorning.amsel.api.PlayerQueue
import io.github.hazymorning.amsel.api.QueueItem
import io.github.hazymorning.amsel.data.PositionAnchor
import io.github.hazymorning.amsel.data.QueueState
import io.github.hazymorning.amsel.playback.PlayRequest
import io.github.hazymorning.amsel.playback.snapshotOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStateUriTest {
    private val track = MediaItem("10", "library", "Dawn Chorus", "library://track/10", "track")
    private val queue =
        PlayerQueue("kitchen", active = true, currentItem = QueueItem("kitchen", "q1", mediaItem = track))
    private val state = QueueState(queue, PositionAnchor(0.0, 0))

    @Test fun `what plays is the active queue's item, else what the speaker reports`() {
        assertEquals(
            "library://track/10",
            playingUri(snapshotOf(Player("kitchen"), state, null, false, null, 0), null),
        )
        // Another source took the speaker over: its queue's item is not what plays.
        val external = Player("kitchen", currentMedia = PlayerMedia(uri = "spotify://track/x"))
        val outside = snapshotOf(external, state.copy(queue = queue.copy(active = false)), null, false, null, 0)
        assertEquals("spotify://track/x", playingUri(outside, null))
        assertNull(playingUri(null, null))
    }

    @Test fun `a play action starting a track shows that track until it ends`() {
        val snapshot = snapshotOf(Player("kitchen"), state, null, false, null, 0)
        val tap = PlayRequest("kitchen", listOf("library://album/4"), startItem = "library://track/9")
        assertEquals("library://track/9", playingUri(snapshot, tap))
        // Play and Shuffle name the album: its tile is marked, not a track the start may replace.
        assertEquals("library://album/4", playingUri(snapshot, PlayRequest("kitchen", listOf("library://album/4"))))
        assertEquals("library://track/10", playingUri(snapshot, null))
    }

    @Test fun `a play with no track to start names what it plays, never a chapter's position`() {
        val snapshot = snapshotOf(Player("kitchen"), state, null, false, null, 0)
        val chapter = PlayRequest("kitchen", listOf("library://audiobook/2"), startItem = "3")
        assertEquals("library://audiobook/2", playingUri(snapshot, chapter))
        val episode = PlayRequest("kitchen", listOf("library://podcast_episode/9"))
        assertEquals("library://podcast_episode/9", playingUri(snapshot, episode))
        val songs = listOf("library://track/4", "library://track/5")
        assertEquals("library://track/4", playingUri(snapshot, PlayRequest("kitchen", songs)))
        assertEquals("library://track/10", playingUri(snapshot, PlayRequest("kitchen", songs, shuffle = true)))
    }
}
