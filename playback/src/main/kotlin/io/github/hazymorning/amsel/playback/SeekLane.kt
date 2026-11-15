package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.data.Holding
import io.github.hazymorning.amsel.data.Place
import io.github.hazymorning.amsel.data.Seeks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One speaker's seeks, at most one on its way at a time. Music Assistant restarts the stream for
 * every seek, starts playing whatever the queue did before, and keeps the old stream's decoders
 * and buffer for five minutes (`audio_buffer.py`, `_monitor_inactivity`): twenty seeks half a
 * second apart left an idle server with 33 ffmpeg processes and 2.6 GB (2.10.9 live, a book from
 * Audiobookshelf; `docs/notes/seeking.md`). So:
 * - taps that follow one another (skips by seconds, steps by chapter) become one seek, sent once
 *   they stop;
 * - the next seek waits for the server's answer to the one before, and only the latest waits;
 * - a speaker that does not play is sent nothing: the place is kept ([Seeks.park]) and its next
 *   play starts there.
 * Where each seek goes shows at once, through [seeks]. While another source plays on the speaker
 * (AirPlay, one of its inputs), the seek is that source's: it goes out the same way, and what shows
 * is what the speaker reports.
 *
 * The lane also keeps a pause. The server carries a play it is starting through, whatever it is
 * told meanwhile: paused while it started a play or a `play_media`, the speaker began to play all
 * the same and played on (2.10.9 and 2.11.0b7, live). So a pause asked while something that starts
 * the stream is on its way goes out again once that has landed, unless playback was asked for by then.
 */
internal class SeekLane(
    private val queueId: String,
    private val seeks: Seeks,
    private val scope: CoroutineScope,
    private val elapsedRealtime: () -> Long,
    /** Sends the seek and returns once the server answered. */
    private val seekOnServer: suspend (seconds: Int) -> Unit,
    private val pauseOnServer: suspend () -> Unit,
    private val onFailed: (error: Exception, retry: () -> Unit) -> Unit,
) {
    private class Waiting(
        val seconds: Int,
        val dueAtMs: Long,
        /** Where it goes in the queue's item, and its ticket there; null for another source's seek. */
        val shown: Pair<Place, Long>?,
        /** A play of a parked queue: where it stays parked should the seek not reach the server. */
        val fromParked: Boolean = false,
        /** The speaker is only starting the item: it may not report holding it yet. */
        val starting: Boolean = false,
    ) {
        /** Done once it was answered, failed, or gave way to another. */
        val done = CompletableDeferred<Unit>()
    }

    private var waiting: Waiting? = null
    private var worker: Job? = null

    /** The seek on its way: sent, and not answered yet. */
    private var flight: Waiting? = null

    /** Plays, skips and picks sent here and not answered yet: the server is starting the stream. */
    private var plays = 0

    /** A pause came while something that starts the stream was on its way: the speaker is paused once that has landed. */
    private var pauseOnLanding = false

    /** The last play or pause asked here, which the speaker takes a moment to report. */
    private var wantsPlay: Boolean? = null
    private var wantedAtMs = 0L

    /** A seek to [seconds] in what the speaker holds; [step] for a tap that others may follow. */
    fun seek(seconds: Int, step: Boolean) {
        synchronized(this) {
            val settleMs = if (step) SETTLE_MS else 0
            val holding = seeks.holding(queueId)
            val place = holding?.let { Place(it.uri, seconds.toDouble()) }
            if (holding == null || place == null || plays(holding)) {
                fly(place, seconds, settleMs)
            } else {
                waiting = null
                seeks.park(queueId, place)
            }
        }
    }

    /**
     * A seek in [place]'s item, which the speaker is starting. Paused since that start was asked,
     * the item rests at the place, and its next play starts there.
     */
    fun seekIn(place: Place) {
        synchronized(this) {
            if (wantsPlay == false) seeks.park(queueId, place) else fly(place, place.seconds.toInt(), starting = true)
        }
    }

    /** A play: [plain] where the queue's own place is right, else a seek to where it was parked. */
    suspend fun play(plain: suspend () -> Unit) {
        val seek = synchronized(this) {
            starting()
            val parked = seeks.parked(queueId) ?: return@synchronized null
            // A book or an episode never starts at 0, which the server takes as "resume".
            fly(parked, parked.seconds.toInt().coerceAtLeast(1), fromParked = true)
        }
        if (seek == null) starts(plain) else seek.done.await()
    }

    /** Another item is asked for with [send], a skip or a pick in Up next: a seek not sent yet belonged to this one. */
    suspend fun skip(send: suspend () -> Unit) {
        dropped()
        starting()
        starts(send)
    }

    /**
     * Playback is being started here by other means (a `play_media`, music moved to this speaker),
     * or was asked again while it starts: a pause waiting for a start gives way.
     */
    fun starting() {
        synchronized(this) {
            wish(true)
            pauseOnLanding = false
        }
    }

    /** [send] starts the stream and returns once the server has. */
    private suspend fun starts(send: suspend () -> Unit) {
        synchronized(this) { plays++ }
        try {
            send()
        } finally {
            synchronized(this) { plays-- }
        }
        landed()
    }

    /** Something that starts the stream has landed: a pause that waited for it goes out, once nothing else is on its way. */
    suspend fun landed() {
        if (synchronized(this) { restsNow() }) pauseLanded()
    }

    /** Call with the lock held. */
    private fun restsNow(): Boolean {
        if (flight != null || waiting != null || plays > 0) return false
        return pauseOnLanding.also { pauseOnLanding = false }
    }

    /**
     * A pause or a stop: the speaker rests where the latest seek goes. One not sent yet waits for
     * the next play. One on its way starts the stream again, as does a play the server is starting,
     * this lane's or another ([starting]): the speaker is paused once that has landed.
     */
    fun paused(starting: Boolean = false) {
        synchronized(this) {
            wish(false)
            pauseOnLanding = starting || flight != null || plays > 0
            val unsent = waiting
            waiting = null
            unsent?.done?.complete(Unit)
            val latest = unsent ?: flight ?: return
            latest.shown?.let { (place, _) -> seeks.park(queueId, Place(place.uri, latest.seconds.toDouble())) }
        }
    }

    /** Another item is coming: a seek not sent yet belonged to this one. */
    fun dropped() {
        synchronized(this) {
            val unsent = waiting ?: return
            waiting = null
            unsent.done.complete(Unit)
            unsent.shown?.let { (_, ticket) -> seeks.failed(queueId, ticket) }
        }
    }

    /** The seek shows at once, at [place], and waits its turn: for [settleMs], and for the one on its way. */
    private fun fly(
        place: Place?,
        seconds: Int,
        settleMs: Long = 0,
        fromParked: Boolean = false,
        starting: Boolean = false,
    ): Waiting {
        pauseOnLanding = false
        waiting?.done?.complete(Unit)
        val shown = place?.let { it to seeks.fly(queueId, it) }
        val seek = Waiting(seconds, elapsedRealtime() + settleMs, shown, fromParked, starting)
        waiting = seek
        if (worker?.isActive != true) worker = scope.launch { run() }
        return seek
    }

    private fun wish(play: Boolean) {
        wantsPlay = play
        wantedAtMs = elapsedRealtime()
    }

    private fun plays(holding: Holding): Boolean =
        wantsPlay?.takeIf { elapsedRealtime() - wantedAtMs < WISH_MS } ?: holding.playing

    private suspend fun run() {
        while (true) {
            val next = settled() ?: return
            send(next)
        }
    }

    /** The seek to send, once its taps have stopped; null when none waits, which ends the worker. */
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
                // Made on an item that has since ended: its target belongs to that item, not the one playing now.
                val shown = next.shown
                val own = next.starting || next.fromParked
                if (shown != null && !own && seeks.holding(queueId)?.uri != shown.first.uri) {
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
        } catch (e: MaConnectionException) {
            e
        } catch (e: MaCommandException) {
            e
        } catch (e: PhoneNotReadyException) {
            e
        }
        val (latest, pause) = synchronized(this) {
            flight = null
            (waiting == null) to restsNow()
        }
        when {
            error == null -> {
                if (ticket != null) seeks.answered(queueId, ticket)
                if (pause) pauseLanded()
            }

            // A newer seek waits: this one's failure changes nothing the person sees.
            latest -> {
                if (ticket != null) seeks.failed(queueId, ticket)
                if (seek.fromParked && place != null) seeks.park(queueId, place)
                onFailed(error) { retry(seek) }
            }
        }
        seek.done.complete(Unit)
    }

    private suspend fun pauseLanded() {
        try {
            pauseOnServer()
        } catch (e: MaConnectionException) {
            onFailed(e) { scope.launch { pauseLanded() } }
        } catch (e: MaCommandException) {
            onFailed(e) { scope.launch { pauseLanded() } }
        }
    }

    private fun retry(seek: Waiting) {
        if (seek.fromParked) scope.launch { play {} } else seek(seek.seconds, step = false)
    }

    companion object {
        /** How long taps may follow one another before their one seek goes out. */
        const val SETTLE_MS = 600L

        /** How long a play or a pause asked here stands for what the speaker does, before its own report counts. */
        const val WISH_MS = 5_000L
    }
}
