package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.NothingToMixException
import io.github.hazymorning.amsel.api.QueueOption
import io.github.hazymorning.amsel.api.endlessMixSeed
import io.github.hazymorning.amsel.api.playEndlessMix
import io.github.hazymorning.amsel.data.ItemPosition
import io.github.hazymorning.amsel.data.SeekTracker
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayRequest(
    val queueId: String,
    val uris: List<String>,
    val option: QueueOption = QueueOption.Replace,
    val startItem: String? = null,
    val shuffle: Boolean = false,
    /** Only shown while the server starts playback, for feedback at once; never sent. */
    val title: String? = null,
    /**
     * Sent only once This phone's Sendspin client has joined (the server forgets a disconnected
     * phone), with its local hold released, or the phone would pause the stream it is sent.
     */
    val onPhone: Boolean = false,
    /**
     * Where a book started at a chapter begins; without a [startItem] leading there, the book is
     * started and then sought there.
     */
    val startPositionMs: Long? = null,
)

/** [sinceMs] on the elapsedRealtime clock. */
data class PendingPlay(val request: PlayRequest, val sinceMs: Long)

/**
 * The server answers `play_media` only once playback starts, which can take tens of seconds, so plays
 * run in an app-wide scope, one per speaker: an identical request meanwhile is a double tap. Of the
 * plays waiting only the latest is kept; every Play next and Add to Up next is kept, in order.
 */
class PlayDispatcher(
    private val api: MaApi,
    private val phone: LocalOutput,
    private val scope: CoroutineScope,
    private val seeks: SeekTracker = SeekTracker.None,
    private val seekIn: (queueId: String, place: ItemPosition) -> Unit = { _, _ -> },
    /** Called for every play that starts playback, a dropped double tap too. */
    private val starting: (queueId: String) -> Unit = {},
    /**
     * Called once the server has songs for a mix on This phone: a download playing there gives way
     * only then, not for a mix that comes to nothing.
     */
    private val phoneMixStarts: () -> Unit = {},
    private val elapsedRealtime: () -> Long,
) {
    private val running = mutableMapOf<String, PlayRequest>()
    private val waiting = mutableMapOf<String, ArrayDeque<PlayRequest>>()
    private val _pending = MutableStateFlow<Map<String, PendingPlay>>(emptyMap())
    private val _failures =
        MutableSharedFlow<CommandFailure>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Per speaker, the latest play that starts playback, running or waiting; never Add or Next. */
    val pending: StateFlow<Map<String, PendingPlay>> = _pending.asStateFlow()

    val failures: SharedFlow<CommandFailure> = _failures.asSharedFlow()

    fun play(request: PlayRequest) {
        if (request.starts) starting(request.queueId)
        synchronized(this) {
            val current = running[request.queueId] ?: return start(request)
            val queue = waiting.getOrPut(request.queueId) { ArrayDeque() }
            if (current == request || queue.lastOrNull() == request) return
            if (request.starts) queue.removeAll { it.starts }
            queue.addLast(request)
            publish(request.queueId)
        }
    }

    private fun start(request: PlayRequest) {
        running[request.queueId] = request
        publish(request.queueId)
        scope.launch {
            val error = try {
                val queueId = if (request.onPhone) {
                    phone.awaitConnected().also { phone.hold(false) }
                } else {
                    request.queueId
                }
                val landsAt = request.startPositionMs
                val uri = request.uris.singleOrNull()
                when {
                    landsAt == null || uri == null -> request.send(queueId)

                    request.startItem != null ->
                        seeks.trackSeek(queueId, ItemPosition(uri, landsAt / MS_PER_SECOND)) { request.send(queueId) }

                    else -> {
                        request.send(queueId)
                        // Into the chapter's first second, and never 0, which the server takes as "resume".
                        val seconds = ceil(landsAt / MS_PER_SECOND).coerceAtLeast(1.0)
                        seekIn(queueId, ItemPosition(uri, seconds))
                    }
                }
                null
            } catch (e: MaException) {
                e
            } catch (e: PhoneNotReadyException) {
                e
            } catch (e: NothingToMixException) {
                e
            } finally {
                finish(request.queueId)
            }
            // Reported after finish(), so a Retry is a new play, not a double tap of this one.
            if (error != null) fail(request, error)
        }
    }

    private suspend fun PlayRequest.send(queueId: String) {
        val mixOf = uris.singleOrNull()?.let(::endlessMixSeed)?.takeIf { starts }
        if (mixOf != null) return api.playEndlessMix(queueId, mixOf) { if (onPhone) phoneMixStarts() }
        api.playMedia(queueId, uris, option, startItem, shuffle)
    }

    private fun fail(request: PlayRequest, error: Exception) {
        // Retried, a mix of nothing fails the same way.
        val retry = { play(request) }.takeIf { error !is NothingToMixException }
        _failures.tryEmit(CommandFailure(request.queueId, error, play = true, retry = retry))
    }

    private fun finish(queueId: String) = synchronized(this) {
        running.remove(queueId)
        val next = waiting[queueId]?.removeFirstOrNull()
        if (waiting[queueId]?.isEmpty() == true) waiting.remove(queueId)
        if (next != null) start(next) else publish(queueId)
    }

    private fun publish(queueId: String) {
        val play = (listOfNotNull(running[queueId]) + waiting[queueId].orEmpty()).lastOrNull { it.starts }
        _pending.update { all ->
            when {
                play == null -> all - queueId
                all[queueId]?.request == play -> all
                else -> all + (queueId to PendingPlay(play, elapsedRealtime()))
            }
        }
    }
}

/** Play next and Add to Up next leave what plays alone. */
private val PlayRequest.starts: Boolean get() = option == QueueOption.Replace

private const val MS_PER_SECOND = 1000.0
