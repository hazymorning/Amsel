package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.data.CurrentItemStatus
import io.github.hazymorning.amsel.data.ItemPosition
import io.github.hazymorning.amsel.data.SeekTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Each seek costs the server a new stream and starts playback (docs/API-NOTES.md, "A seek starts
 * playback"): one goes out at a time, taps within [SETTLE_MS] become one, and none while paused. The
 * server overruns a pause asked during a start ("A pause or a stop"), so it is sent again after it.
 */
internal class SeekController(
    private val queueId: String,
    private val seeks: SeekTracker,
    private val scope: CoroutineScope,
    private val elapsedRealtime: () -> Long,
    /** Returns once the server has answered. */
    private val seekOnServer: suspend (seconds: Int) -> Unit,
    private val pauseOnServer: suspend () -> Unit,
    private val onFailed: (error: Exception, retry: () -> Unit) -> Unit,
) {
    private class Waiting(
        val seconds: Int,
        val dueAtMs: Long,
        /** The place and its SeekTracker ticket; null for another source's seek. */
        val shown: Pair<ItemPosition, Long>?,
        /** A play from the paused position, which is set again should the seek fail. */
        val fromParked: Boolean = false,
        /** The speaker is only starting the item: it may not report holding it yet. */
        val starting: Boolean = false,
    ) {
        /** Done once it was answered, failed, or gave way to another. */
        val done = CompletableDeferred<Unit>()
    }

    private var waiting: Waiting? = null
    private var worker: Job? = null

    /** Sent and not answered yet. */
    private var flight: Waiting? = null

    /** Plays, skips and picks sent here and not answered yet. */
    private var plays = 0

    /** A pause came while a start was unanswered: it is sent again once that is answered. */
    private var pauseOnLanding = false

    /** The last play or pause asked here: for [WISH_MS] it counts over the speaker's slower report. */
    private var wantsPlay: Boolean? = null
    private var wantedAtMs = 0L

    /** [step] for a tap that others may follow, which waits [SETTLE_MS]. */
    fun seek(seconds: Int, step: Boolean) {
        synchronized(this) {
            val settleMs = if (step) SETTLE_MS else 0
            val currentItemStatus = seeks.currentItemStatus(queueId)
            val place = currentItemStatus?.let { ItemPosition(it.uri, seconds.toDouble()) }
            if (currentItemStatus == null || place == null || plays(currentItemStatus)) {
                startSeek(place, seconds, settleMs)
            } else {
                waiting = null
                seeks.setPausedPosition(queueId, place)
            }
        }
    }

    /** For an item the speaker is starting; if paused since, the place becomes the paused position. */
    fun seekIn(place: ItemPosition) {
        synchronized(this) {
            if (wantsPlay ==
                false
            ) {
                seeks.setPausedPosition(queueId, place)
            } else {
                startSeek(place, place.seconds.toInt(), starting = true)
            }
        }
    }

    /** A seek to the paused position replaces [plain], as a seek starts playback. */
    suspend fun play(plain: suspend () -> Unit) {
        val seek = synchronized(this) {
            starting()
            val pausedPosition = seeks.pausedPosition(queueId) ?: return@synchronized null
            // A book or an episode never starts at 0, which the server takes as "resume".
            startSeek(pausedPosition, pausedPosition.seconds.toInt().coerceAtLeast(1), fromParked = true)
        }
        if (seek == null) starts(plain) else seek.done.await()
    }

    /** A waiting seek belonged to the item being left, so it is dropped. */
    suspend fun skip(send: suspend () -> Unit) {
        dropped()
        starting()
        starts(send)
    }

    /** Playback is starting by other means, or was asked again: a pause waiting for a start is dropped. */
    fun starting() {
        synchronized(this) {
            wish(true)
            pauseOnLanding = false
        }
    }

    private suspend fun starts(send: suspend () -> Unit) {
        synchronized(this) { plays++ }
        try {
            send()
        } finally {
            synchronized(this) { plays-- }
        }
        streamStarted()
    }

    /** A start was answered: a pause that waited goes out once nothing else is unanswered. */
    suspend fun streamStarted() {
        if (synchronized(this) { pauseDueNow() }) pauseAfterStart()
    }

    /** Call with the lock held. */
    private fun pauseDueNow(): Boolean {
        if (flight != null || waiting != null || plays > 0) return false
        return pauseOnLanding.also { pauseOnLanding = false }
    }

    /**
     * The latest seek's place becomes the paused position; one not sent yet waits for the next play.
     * A seek or a play still unanswered, here or elsewhere ([starting]), restarts the stream: the pause
     * is sent again after it.
     */
    fun paused(starting: Boolean = false) {
        synchronized(this) {
            wish(false)
            pauseOnLanding = starting || flight != null || plays > 0
            val unsent = waiting
            waiting = null
            unsent?.done?.complete(Unit)
            val latest = unsent ?: flight ?: return
            latest.shown?.let { (place, _) ->
                seeks.setPausedPosition(queueId, ItemPosition(place.uri, latest.seconds.toDouble()))
            }
        }
    }

    /** Another item is coming: a waiting seek belonged to this one and fails. */
    fun dropped() {
        synchronized(this) {
            val unsent = waiting ?: return
            waiting = null
            unsent.done.complete(Unit)
            unsent.shown?.let { (_, ticket) -> seeks.failed(queueId, ticket) }
        }
    }

    private fun startSeek(
        place: ItemPosition?,
        seconds: Int,
        settleMs: Long = 0,
        fromParked: Boolean = false,
        starting: Boolean = false,
    ): Waiting {
        pauseOnLanding = false
        waiting?.done?.complete(Unit)
        val shown = place?.let { it to seeks.startSeek(queueId, it) }
        val seek = Waiting(seconds, elapsedRealtime() + settleMs, shown, fromParked, starting)
        waiting = seek
        if (worker?.isActive != true) worker = scope.launch { run() }
        return seek
    }

    private fun wish(play: Boolean) {
        wantsPlay = play
        wantedAtMs = elapsedRealtime()
    }

    private fun plays(currentItemStatus: CurrentItemStatus): Boolean =
        wantsPlay?.takeIf { elapsedRealtime() - wantedAtMs < WISH_MS } ?: currentItemStatus.playing

    private suspend fun run() {
        while (true) {
            val next = settled() ?: return
            send(next)
        }
    }

    private suspend fun settled(): Waiting? {
        while (true) {
            val wait = synchronized(this) {
                val next = waiting
                if (next == null) {
                    worker = null
                    return null
                }
                val left = next.dueAtMs - elapsedRealtime()
                if (left > 0) return@synchronized left
                waiting = null
                // A seek made on an item that has since ended belongs to that item.
                val shown = next.shown
                val own = next.starting || next.fromParked
                if (shown != null && !own && seeks.currentItemStatus(queueId)?.uri != shown.first.uri) {
                    seeks.failed(queueId, shown.second)
                    next.done.complete(Unit)
                    return@synchronized 0L
                }
                flight = next
                return next
            }
            delay(wait)
        }
    }

    private suspend fun send(seek: Waiting) {
        val (place, ticket) = seek.shown ?: (null to null)
        if (ticket != null) seeks.sent(queueId, ticket)
        val error = try {
            seekOnServer(seek.seconds)
            null
        } catch (e: MaException) {
            e
        } catch (e: PhoneNotReadyException) {
            e
        }
        val (latest, pause) = synchronized(this) {
            flight = null
            (waiting == null) to pauseDueNow()
        }
        when {
            error == null -> {
                if (ticket != null) seeks.answered(queueId, ticket)
                if (pause) pauseAfterStart()
            }

            // Reported only when no newer seek waits; otherwise the failure changes nothing shown.
            latest -> {
                if (ticket != null) seeks.failed(queueId, ticket)
                if (seek.fromParked && place != null) seeks.setPausedPosition(queueId, place)
                onFailed(error) { retry(seek) }
            }
        }
        seek.done.complete(Unit)
    }

    private suspend fun pauseAfterStart() {
        try {
            pauseOnServer()
        } catch (e: MaException) {
            onFailed(e) { scope.launch { pauseAfterStart() } }
        }
    }

    private fun retry(seek: Waiting) {
        if (seek.fromParked) scope.launch { play {} } else seek(seek.seconds, step = false)
    }

    companion object {
        /** Taps closer than this become one seek. */
        const val SETTLE_MS = 600L

        const val WISH_MS = 5_000L
    }
}
