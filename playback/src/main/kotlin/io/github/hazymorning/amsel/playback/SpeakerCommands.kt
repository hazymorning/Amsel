package io.github.hazymorning.amsel.playback

import androidx.annotation.StringRes
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.NothingToMixException
import io.github.hazymorning.amsel.api.QueueOption
import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.data.ItemPosition
import io.github.hazymorning.amsel.data.SeekTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Each returns once the server replied; [seek] returns at once, [playMedia] once the play is handed to
 * the PlayDispatcher. The server restarts the track on [previous] after 5 s. The volume commands are
 * the group ones: a sync leader moves its whole group, a lone speaker its own volume.
 */
interface SpeakerCommands {
    suspend fun play(playerId: String)
    suspend fun pause(playerId: String)
    suspend fun stop(playerId: String)
    suspend fun next(playerId: String)
    suspend fun previous(playerId: String)

    /**
     * Returns at once: the place shows at once and the command follows ([SeekController]). [step] for a
     * skip by seconds or a chapter step, which more may follow.
     */
    fun seek(playerId: String, positionSeconds: Int, step: Boolean = false)
    suspend fun playIndex(playerId: String, queueItemId: String)
    suspend fun setVolume(playerId: String, level: Int)
    suspend fun volumeUp(playerId: String)
    suspend fun volumeDown(playerId: String)
    suspend fun setShuffle(playerId: String, enabled: Boolean)
    suspend fun setRepeat(playerId: String, mode: RepeatMode)
    suspend fun playMedia(request: PlayRequest)

    /**
     * Playback is starting by other means (a play from a list, music moved there), or was asked again:
     * nothing is sent, and a pause waiting for a start is dropped.
     */
    fun starting(playerId: String) = Unit
}

class CommandFailure(
    val playerId: String,
    val error: Throwable,
    val play: Boolean = false,
    /** Null where a retry would fail the same way. */
    val retry: (() -> Unit)?,
) {
    /** May hold the speaker's name as `%1$s`. */
    @get:StringRes
    val text: Int get() = failureText(error, play)
}

/** [play] for a `play_media` that did not start. */
@StringRes
fun failureText(error: Throwable, play: Boolean = false): Int = when {
    error is PhoneNotReadyException && error.refused -> R.string.error_phone_refused
    error is PhoneNotReadyException -> R.string.error_phone_not_ready
    error is NothingToMixException -> R.string.error_nothing_to_mix
    error !is MaCommandException -> R.string.error_not_connected
    error.isSpeakerUnresponsive -> R.string.error_speaker_unavailable
    error.isPermissionError -> R.string.error_not_allowed
    error.isBusy -> R.string.error_speaker_busy
    error.isNotFound -> R.string.error_not_found
    play -> R.string.error_play_failed
    else -> R.string.error_generic
}

/**
 * [stopped]: the Sendspin client stopped for good, and only a new connection changes that. [refused]:
 * the server turned it away (unpaired, not authorized, an incompatible protocol).
 */
class PhoneNotReadyException(val refused: Boolean = false, val stopped: Boolean = refused) :
    Exception("This phone has not joined the server")

/**
 * Each command [wake]s the session first: one from the notification, a headset or the car may come
 * after the socket's idle close, and nothing else would reopen it.
 */
class MaSpeakerCommands(
    private val api: MaApi,
    private val dispatcher: PlayDispatcher,
    private val wake: () -> Unit,
    private val seeks: SeekTracker,
    private val seekDispatcher: SeekDispatcher,
    private val scope: CoroutineScope,
) : SpeakerCommands {
    val failures: SharedFlow<CommandFailure> get() = seekDispatcher.failures

    override suspend fun play(playerId: String) = controller(playerId).play { awake { api.play(playerId) } }

    override suspend fun pause(playerId: String) = sendPauseOrStop(playerId) { api.pause(playerId) }

    override suspend fun stop(playerId: String) = sendPauseOrStop(playerId) { api.stop(playerId) }

    /**
     * The server overruns a pause asked while it starts a play ([SeekController]): the controller is told
     * here of a `play_media` on its way or a start the server reports, and pauses again after it.
     */
    private suspend fun sendPauseOrStop(playerId: String, command: suspend () -> Unit) {
        val controller = controller(playerId)
        val play = dispatcher.pending.value[playerId]
        val starting = play != null || seeks.currentItemStatus(playerId)?.starting == true
        controller.paused(starting)
        awake { command() }
        if (!starting) return
        scope.launch {
            withTimeoutOrNull(START_WAIT_MS) {
                if (play != null) dispatcher.pending.first { it[playerId] != play }
                seeks.awaitStarted(playerId)
            } ?: return@launch
            controller.streamStarted()
        }
    }

    override suspend fun next(playerId: String) = controller(playerId).skip { awake { api.next(playerId) } }

    override suspend fun previous(playerId: String) = controller(playerId).skip { awake { api.previous(playerId) } }

    override fun seek(playerId: String, positionSeconds: Int, step: Boolean) =
        controller(playerId).seek(positionSeconds, step)

    /** For an item [queueId] is starting: a book started at a chapter, or a paused position moved by Play on. */
    fun seekIn(queueId: String, place: ItemPosition) = seekDispatcher.seekIn(queueId, place)

    override suspend fun playIndex(playerId: String, queueItemId: String) =
        controller(playerId).skip { awake { api.playIndex(playerId, queueItemId) } }

    override suspend fun setVolume(playerId: String, level: Int) = awake { api.setVolume(playerId, level) }

    override suspend fun volumeUp(playerId: String) = awake { api.volumeUp(playerId) }

    override suspend fun volumeDown(playerId: String) = awake { api.volumeDown(playerId) }

    override suspend fun setShuffle(playerId: String, enabled: Boolean) = awake { api.setShuffle(playerId, enabled) }

    override suspend fun setRepeat(playerId: String, mode: RepeatMode) = awake { api.setRepeat(playerId, mode) }

    override suspend fun playMedia(request: PlayRequest) {
        if (request.option == QueueOption.Replace) controller(request.queueId).dropped()
        awake { dispatcher.play(request) }
    }

    override fun starting(playerId: String) = seekDispatcher.starting(playerId)

    private fun controller(playerId: String): SeekController = seekDispatcher.controller(playerId)

    private inline fun awake(command: () -> Unit) {
        wake()
        command()
    }
}

private const val START_WAIT_MS = 120_000L

internal inline fun SeekTracker.trackSeek(queueId: String, place: ItemPosition, send: () -> Unit) {
    val ticket = startSeek(queueId, place)
    sent(queueId, ticket)
    var answered = false
    try {
        send()
        answered = true
    } finally {
        if (answered) answered(queueId, ticket) else failed(queueId, ticket)
    }
}
