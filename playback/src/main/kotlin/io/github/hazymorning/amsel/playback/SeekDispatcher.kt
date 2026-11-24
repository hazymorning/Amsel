package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.data.ItemPosition
import io.github.hazymorning.amsel.data.SeekTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One [SeekController] per speaker, shared by [PlayDispatcher] and [MaSpeakerCommands]. */
class SeekDispatcher(
    private val api: MaApi,
    private val wake: () -> Unit,
    private val seeks: SeekTracker,
    private val phone: LocalOutput,
    private val scope: CoroutineScope,
    private val elapsedRealtime: () -> Long,
) {
    private val controllers = mutableMapOf<String, SeekController>()
    private val _failures =
        MutableSharedFlow<CommandFailure>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val failures: SharedFlow<CommandFailure> = _failures.asSharedFlow()

    fun seekIn(queueId: String, place: ItemPosition) = controller(queueId).seekIn(place)

    fun starting(playerId: String) = controller(playerId).starting()

    internal fun controller(playerId: String): SeekController = synchronized(controllers) {
        controllers.getOrPut(playerId) {
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
