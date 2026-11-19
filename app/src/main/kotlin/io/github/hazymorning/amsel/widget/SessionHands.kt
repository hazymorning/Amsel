package io.github.hazymorning.amsel.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
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

/** What a widget's tap asks of Amsel. */
interface WidgetHands {
    /** Opens the connection, as a command from outside the app does: what the speaker holds is known sooner. */
    fun wake()

    suspend fun play()

    suspend fun pause()

    suspend fun next()

    suspend fun previous()

    suspend fun volume(up: Boolean)

    suspend fun play(item: WidgetItem)
}

/**
 * The widget's taps, put to the one playback session as Amsel's own buttons put theirs: through a
 * controller, which starts the service when it is not running and lets it go after the command.
 * So the widget keeps Amsel's rules: a book's skips, the volume limit, a failure said plainly.
 * Main thread only.
 */
internal class SessionHands(
    private val context: Context,
    private val wakeSession: () -> Unit,
    /** The session holds This phone, whose volume is the phone's own. */
    private val onPhone: () -> Boolean,
    /** The tap shows at once on the widgets and in Amsel, before the speaker answers. */
    private val showPlaying: (Boolean) -> Unit,
) : WidgetHands {
    override fun wake() = wakeSession()

    /**
     * Play as a headset's play: through the media button, which starts the service in front at once
     * and resumes what the speaker holds, with the "Amsel" stand-in until it reports.
     */
    override suspend fun play() {
        showPlaying(true)
        val press = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setClass(context, PlaybackService::class.java)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        try {
            ContextCompat.startForegroundService(context, press)
        } catch (e: IllegalStateException) {
            // Not from a tap, so not allowed in front: Android's own refusal, nothing to say.
        }
    }

    override suspend fun pause() {
        showPlaying(false)
        control(Player.COMMAND_PLAY_PAUSE) { it.pause() }
    }

    /** A book's or an episode's step is 30 s, as the dock's; for music, the next song. */
    override suspend fun next() = control(Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_TO_NEXT) {
        if (it.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) it.seekForward() else it.seekToNext()
    }

    override suspend fun previous() = control(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_TO_PREVIOUS) {
        if (it.isCommandAvailable(Player.COMMAND_SEEK_BACK)) it.seekBack() else it.seekToPrevious()
    }

    override suspend fun volume(up: Boolean) {
        if (onPhone()) {
            val audio = context.getSystemService(AudioManager::class.java) ?: return
            val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            return
        }
        control(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS) {
            if (up) it.increaseDeviceVolume(0) else it.decreaseDeviceVolume(0)
        }
    }

    /** As a car's play: the session's speaker plays [item], and shows its title while it starts. */
    override suspend fun play(item: WidgetItem) {
        control(Player.COMMAND_SET_MEDIA_ITEM) {
            val metadata = MediaMetadata.Builder().setTitle(item.title).build()
            it.setMediaItem(MediaItem.Builder().setMediaId(item.uri).setMediaMetadata(metadata).build())
            it.play()
        }
    }

    /**
     * Connects, waits until the speaker offers one of [commands] (it may not have reported yet: Amsel
     * was closed), runs [command], and lets go once the command is on its way.
     */
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
        /** A speaker reports within a second or two of the connection opening. */
        const val OFFER_WAIT_MS = 5_000L
        const val POLL_MS = 100L

        /** Long enough for the session to take the command before the controller lets go. */
        const val SETTLE_MS = 500L
    }
}
