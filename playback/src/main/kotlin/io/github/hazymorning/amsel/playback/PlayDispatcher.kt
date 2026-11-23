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
    /** Shown while the server starts playback, so the tap has visible feedback at once. */
    val title: String? = null,
    /**
     * For This phone, sent only once its Sendspin client has joined the server (a remembered id is
     * not enough: the server forgets a disconnected phone), and with its local hold released, or
     * the phone would pause the stream it is sent.
     */
    val onPhone: Boolean = false,
    /**
     * Where a book started at a chapter begins: the place its reports must reach. Without a
     * [startItem] that leads there, the book is started and then sent there.
     */
    val startPositionMs: Long? = null,
)

/** A play on its way to its speaker, asked for at [sinceMs] (SystemClock.elapsedRealtime). */
data class PendingPlay(val request: PlayRequest, val sinceMs: Long)

/**
 * Sends every `play_media`. The server answers only once playback starts, which can take tens of
 * seconds, so requests run in an app-wide scope, one per speaker at a time: an identical request
 * while one runs is a double tap and is dropped. Others wait their turn: of the plays waiting only
 * the latest survives, while every Play next and Add to Up next is kept, in order.
 */
class PlayDispatcher(
    private val api: MaApi,
    private val phone: LocalOutput,
    private val scope: CoroutineScope,
    /** Told where a book started at a chapter begins, before the play goes out. */
    private val seeks: SeekTracker = SeekTracker.None,
    /** Sends a queue that is starting a book to a place in it ([SeekDispatcher.seekIn]). */
    private val seekIn: (queueId: String, place: ItemPosition) -> Unit = { _, _ -> },
    /** Told of every play asked that starts playback, a double tap too ([SpeakerCommands.starting]). */
    private val starting: (queueId: String) -> Unit = {},
    /**
     * Told that a mix is about to start on This phone, once the server has songs for it: what the
     * phone plays from its own files gives way only then, not for a mix that comes to nothing.
     */
    private val phoneMixStarts: () -> Unit = {},
    private val elapsedRealtime: () -> Long,
) {
    private val running = mutableMapOf<String, PlayRequest>()
    private val waiting = mutableMapOf<String, ArrayDeque<PlayRequest>>()
    private val _pending = MutableStateFlow<Map<String, PendingPlay>>(emptyMap())
    private val _failures =
        MutableSharedFlow<CommandFailure>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The play each speaker is starting, running or waiting, for optimistic UI; Add and Next start nothing. */
    val pending: StateFlow<Map<String, PendingPlay>> = _pending.asStateFlow()

    val failures: SharedFlow<CommandFailure> = _failures.asSharedFlow()

    /** Starts [request], or queues it behind the one running; a double tap is dropped. */
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
            // Said once the play has finished, so a Retry is a new play, not a double tap of this one.
            if (error != null) fail(request, error)
        }
    }

    private suspend fun PlayRequest.send(queueId: String) {
        val mixOf = uris.singleOrNull()?.let(::endlessMixSeed)?.takeIf { starts }
        if (mixOf != null) return api.playEndlessMix(queueId, mixOf) { if (onPhone) phoneMixStarts() }
        api.playMedia(queueId, uris, option, startItem, shuffle)
    }

    private fun fail(request: PlayRequest, error: Exception) {
        // Asked again, a mix of nothing is still a mix of nothing.
        val retry = { play(request) }.takeIf { error !is NothingToMixException }
        _failures.tryEmit(CommandFailure(request.queueId, error, play = true, retry = retry))
    }

    private fun finish(queueId: String) = synchronized(this) {
        running.remove(queueId)
        val next = waiting[queueId]?.removeFirstOrNull()
        if (waiting[queueId]?.isEmpty() == true) waiting.remove(queueId)
        if (next != null) start(next) else publish(queueId)
    }

    /** The latest play on its way to [queueId], running or waiting, since it was first asked for. */
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

/** Replacing Up next starts playback; Play next and Add to Up next leave what plays alone. */
private val PlayRequest.starts: Boolean get() = option == QueueOption.Replace

private const val MS_PER_SECOND = 1000.0
