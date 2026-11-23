package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.data.ItemPosition
import io.github.hazymorning.amsel.data.SeekTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One [SeekController] a speaker, built on first use: the plays ([PlayDispatcher]) and the commands
 * ([MaSpeakerCommands]) share them. A seek [wake]s the session first, and one for This phone waits
 * until its Sendspin client has joined the server, and releases its output.
 */
class SeekDispatcher(
    private val api: MaApi,
    private val wake: () -> Unit,
    private val seeks: SeekTracker,
    private val phone: LocalOutput,
    private val scope: CoroutineScope,
    private val elapsedRealtime: () -> Long,
) {
    private val seekDispatcher = mutableMapOf<String, SeekController>()
    private val _failures =
        MutableSharedFlow<CommandFailure>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Seeks that did not reach their speaker. */
    val failures: SharedFlow<CommandFailure> = _failures.asSharedFlow()

    /** A seek in [place]'s item, which [queueId] is starting: a book started at a chapter no `start_item` leads to. */
    fun seekIn(queueId: String, place: ItemPosition) = controller(queueId).seekIn(place)

    /** A play that starts playback was asked of [playerId] ([SpeakerCommands.starting]). */
    fun starting(playerId: String) = controller(playerId).starting()

    internal fun controller(playerId: String): SeekController = synchronized(seekDispatcher) {
        seekDispatcher.getOrPut(playerId) {
            SeekController(
                playerId,
                seeks,
                scope,
                elapsedRealtime,
                seekOnServer = { seconds ->
                    val id = if (playerId == phone.id) phone.awaitConnected().also { phone.hold(false) } else playerId
                    wake()
                    api.seek(id, seconds)
                },
                pauseOnServer = {
                    wake()
                    api.pause(playerId)
                },
                onFailed = { error, retry -> _failures.tryEmit(CommandFailure(playerId, error, retry = retry)) },
            )
        }
    }
}
