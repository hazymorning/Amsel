package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.PlayerQueue

/**
 * Where a queue's playback position was at a known moment on this phone's monotonic clock.
 * The UI extrapolates from it while playing, at the queue's speed (a book's or episode's speed
 * set in Music Assistant); the server only sends time on jumps or drift.
 */
data class PositionAnchor(val seconds: Double, val atElapsedMs: Long)

/**
 * A speaker's queue and where its playback is, kept as one: a new item never shows the last one's
 * time. While [seeking], [position] is where the app sent the queue, standing still until the
 * server reports it there. While [resting], the app has put the queue to rest at [position], and
 * a report that it plays is a moment behind.
 */
data class QueueState(
    val queue: PlayerQueue,
    val position: PositionAnchor,
    val seeking: Boolean = false,
    val resting: Boolean = false,
) {
    /** It plays, as far as the app knows. */
    val isPlaying: Boolean get() = queue.isPlaying && !resting

    /** Where the item is at [nowMs] on the phone's monotonic clock, in seconds. */
    fun secondsAt(nowMs: Long): Double {
        if (seeking || !isPlaying) return position.seconds
        return position.seconds + (nowMs - position.atElapsedMs).coerceAtLeast(0) / MS_PER_SECOND * queue.speed
    }

    private companion object {
        const val MS_PER_SECOND = 1000.0
    }
}
