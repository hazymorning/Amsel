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
 * Commands for one speaker, each returning once the server replied, except [seek]. [previous]
 * restarts the track once more than 5 s have played: the server decides. The volume commands are
 * the group ones: a sync leader's whole group, a lone speaker's own volume, up and down in the
 * server's adaptive steps. [playMedia] returns once the play is handed to the PlayDispatcher, not
 * when playback starts, which can take tens of seconds.
 */
interface SpeakerCommands {
    suspend fun play(playerId: String)
    suspend fun pause(playerId: String)
    suspend fun stop(playerId: String)
    suspend fun next(playerId: String)
    suspend fun previous(playerId: String)

    /**
     * Takes [playerId] to [positionSeconds] in what it holds and returns at once: the place shows at
     * once, and the command follows ([SeekController]). [step] for a skip by seconds or a step to another
     * chapter, which more may follow.
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
     * Playback on [playerId] is being started by other means than these commands (a play from a
     * list, music moved there), or was asked again while it starts. Nothing is sent: a pause that
     * still waits for a start gives way.
     */
    fun starting(playerId: String) = Unit
}

/** A command or a play ([play]) that did not reach speaker [playerId], and how to send it again. */
class CommandFailure(
    val playerId: String,
    val error: Throwable,
    val play: Boolean = false,
    /** Sends it again; null where that would end the same. */
    val retry: (() -> Unit)?,
) {
    /** What to say; it may name the speaker (`%1$s`). */
    @get:StringRes
    val text: Int get() = failureText(error, play)
}

/** The sentence for a server request that failed; [play] for a `play_media` that did not start. */
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
 * "This phone" did not join the server, so a command for it could not be sent: not in time, or its
 * Sendspin client [stopped] for good, which only a new connection changes: turned away by the
 * server ([refused]: unpaired, not authorized, an incompatible protocol), or unable to start here.
 */
class PhoneNotReadyException(val refused: Boolean = false, val stopped: Boolean = refused) :
    Exception("This phone has not joined the server")

/**
 * Speaker commands on the real server, one each, as the official app sends them; play actions go
 * through the [PlayDispatcher] and seeks through [seekDispatcher]. Each one [wake]s the session first: a
 * command from the notification, a headset or the car may arrive after the socket's idle close,
 * and nothing else would reopen it.
 */
class MaSpeakerCommands(
    private val api: MaApi,
    private val dispatcher: PlayDispatcher,
    private val wake: () -> Unit,
    private val seeks: SeekTracker,
    private val seekDispatcher: SeekDispatcher,
    private val scope: CoroutineScope,
) : SpeakerCommands {
    /** Seeks that did not reach their speaker. */
    val failures: SharedFlow<CommandFailure> get() = seekDispatcher.failures

    override suspend fun play(playerId: String) = controller(playerId).play { awake { api.play(playerId) } }

    override suspend fun pause(playerId: String) = sendPauseOrStop(playerId) { api.pause(playerId) }

    override suspend fun stop(playerId: String) = sendPauseOrStop(playerId) { api.stop(playerId) }

    /**
     * A pause or a stop. One asked while the server starts a play is taken and then overrun
     * ([SeekController]): the lane sends a pause after its own plays and seeks, and here it is told of a
     * `play_media` on its way and of a start the server reports, once those are over.
     */
    private suspend fun sendPauseOrStop(playerId: String, command: suspend () -> Unit) {
        val controller = controller(playerId)
        val play = dispatcher.pending.value[playerId]
        val starting = play != null || seeks.queueStatus(playerId)?.starting == true
        controller.paused(starting)
        awake { command() }
        if (!starting) return
        scope.launch {
            withTimeoutOrNull(START_WAIT_MS) {
                if (play != null) dispatcher.pending.first { it[playerId] != play }
                seeks.started(playerId)
            } ?: return@launch
            controller.streamStarted()
        }
    }

    override suspend fun next(playerId: String) = controller(playerId).skip { awake { api.next(playerId) } }

    override suspend fun previous(playerId: String) = controller(playerId).skip { awake { api.previous(playerId) } }

    override fun seek(playerId: String, positionSeconds: Int, step: Boolean) =
        controller(playerId).seek(positionSeconds, step)

    /** A seek in [place]'s item, which [queueId] is starting: a book started at a chapter no `start_item` leads to. */
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

/** How long a play the server is starting may take before a pause stops waiting for it. */
private const val START_WAIT_MS = 120_000L

/**
 * Runs [send], which takes [queueId] to [place] (a book started at a chapter): the place is told
 * first, and whether the server answered or the command failed.
 */
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
