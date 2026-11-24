package io.github.hazymorning.amsel.widget

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Taps for a widget kept to its own speaker, through a player made for that speaker per command. Main thread only. */
internal class SpeakerTransport(
    private val wakeSession: () -> Unit,
    private val player: () -> Player,
    private val showPlaying: (Boolean) -> Unit,
) : WidgetTransport {
    override fun wake() = wakeSession()

    override suspend fun play() {
        showPlaying(true)
        control(Player.COMMAND_PLAY_PAUSE) { it.play() }
    }

    override suspend fun pause() {
        showPlaying(false)
        control(Player.COMMAND_PLAY_PAUSE) { it.pause() }
    }

    override suspend fun next() = control(Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_NEXT) {
        if (it.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) it.seekForward() else it.seekToNext()
    }

    override suspend fun previous() = control(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_TO_PREVIOUS) {
        if (it.isCommandAvailable(Player.COMMAND_SEEK_BACK)) it.seekBack() else it.seekToPrevious()
    }

    override suspend fun play(item: WidgetItem) = control(Player.COMMAND_SET_MEDIA_ITEM) {
        val metadata = MediaMetadata.Builder().setTitle(item.title).build()
        it.setMediaItem(MediaItem.Builder().setMediaId(item.uri).setMediaMetadata(metadata).build())
        it.play()
    }

    /** Waits for one of [commands] to be offered: after a cold start the speaker has not reported yet. */
    private suspend fun control(vararg commands: Int, command: (Player) -> Unit) {
        val player = player()
        try {
            val offered = withTimeoutOrNull(OFFER_WAIT_MS) {
                while (commands.none(player::isCommandAvailable)) delay(POLL_MS)
            } != null
            if (offered) {
                command(player)
                delay(SETTLE_MS)
            }
        } finally {
            player.release()
        }
    }

    private companion object {
        /** A speaker's first report usually comes 1 to 2 s after the connection opens. */
        const val OFFER_WAIT_MS = 5_000L
        const val POLL_MS = 100L

        /** Time for the command to go out before the player is released. */
        const val SETTLE_MS = 500L
    }
}
