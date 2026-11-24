package io.github.hazymorning.amsel.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.hazymorning.amsel.playback.PlaybackService
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Taps on the speaker Amsel plays on, through a MediaController to Amsel's own session, so the
 * session's rules apply (a book's 30 s skips). Main thread only, as MediaController requires.
 */
internal class SessionTransport(
    private val context: Context,
    private val wakeSession: () -> Unit,
    private val showPlaying: (Boolean) -> Unit,
) : WidgetTransport {
    override fun wake() = wakeSession()

    /**
     * A media button press, as a headset's: the service starts in the foreground at once and resumes
     * the speaker's queue.
     */
    override suspend fun play() {
        showPlaying(true)
        val press = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setClass(context, PlaybackService::class.java)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        try {
            ContextCompat.startForegroundService(context, press)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException outside a tap's start window: nothing to tell the user.
        }
    }

    override suspend fun pause() {
        showPlaying(false)
        control(Player.COMMAND_PLAY_PAUSE) { it.pause() }
    }

    /** The session offers seeking forward and back only for a book or an episode (30 s, 15 s). */
    override suspend fun next() = control(Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_NEXT) {
        if (it.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) it.seekForward() else it.seekToNext()
    }

    override suspend fun previous() = control(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_TO_PREVIOUS) {
        if (it.isCommandAvailable(Player.COMMAND_SEEK_BACK)) it.seekBack() else it.seekToPrevious()
    }

    override suspend fun play(item: WidgetItem) {
        control(Player.COMMAND_SET_MEDIA_ITEM) {
            val metadata = MediaMetadata.Builder().setTitle(item.title).build()
            it.setMediaItem(MediaItem.Builder().setMediaId(item.uri).setMediaMetadata(metadata).build())
            it.play()
        }
    }

    /** Waits for one of [commands] to be offered: after a cold start the speaker has not reported yet. */
    private suspend fun control(vararg commands: Int, command: (MediaController) -> Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controller = MediaController.Builder(context, token).buildAsync().connected() ?: return
        try {
            val offered = withTimeoutOrNull(OFFER_WAIT_MS) {
                while (commands.none(controller::isCommandAvailable)) delay(POLL_MS)
            } != null
            if (offered) {
                command(controller)
                delay(SETTLE_MS)
            }
        } finally {
            controller.release()
        }
    }

    private suspend fun ListenableFuture<MediaController>.connected(): MediaController? =
        suspendCancellableCoroutine { cont ->
            addListener(
                {
                    val connected = try {
                        if (isCancelled) null else get()
                    } catch (e: ExecutionException) {
                        null
                    }
                    if (cont.isActive) cont.resume(connected) else connected?.release()
                },
                context.mainExecutor,
            )
            cont.invokeOnCancellation { MediaController.releaseFuture(this) }
        }

    private companion object {
        /** A speaker's first report usually comes 1 to 2 s after the connection opens. */
        const val OFFER_WAIT_MS = 5_000L
        const val POLL_MS = 100L

        /** Time for the session to take the command before the controller is released. */
        const val SETTLE_MS = 500L
    }
}
