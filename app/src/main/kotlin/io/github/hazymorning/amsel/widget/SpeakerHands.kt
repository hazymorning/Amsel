package io.github.hazymorning.amsel.widget

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A widget's taps on a speaker of its own, whichever Amsel is on: put to that speaker as Amsel's own
 * buttons put theirs to the one it plays on, through a player for it alone ([player]), let go after
 * the command. So a book's skips hold here too. Main thread only.
 */
internal class SpeakerHands(
    private val wakeSession: () -> Unit,
    private val player: () -> Player,
    /** The tap shows at once on the widgets and in Amsel, before the speaker answers. */
    private val showPlaying: (Boolean) -> Unit,
) : WidgetHands {
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

    /**
     * Waits until the speaker offers one of [commands] (it may not have reported yet: Amsel was
     * closed), runs [command], and lets the player go once the command is on its way.
     */
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
        /** A speaker reports within a second or two of the connection opening. */
        const val OFFER_WAIT_MS = 5_000L
        const val POLL_MS = 100L

        /** Long enough for the command to leave before the player lets go. */
        const val SETTLE_MS = 500L
    }
}
