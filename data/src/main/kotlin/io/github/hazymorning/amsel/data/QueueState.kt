package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.PlayerQueue

/**
 * [atElapsedMs] is on the phone's monotonic clock. The server sends time only on jumps and drift, so the UI
 * extrapolates at the queue's speed while playing. See docs/API-NOTES.md, "Events".
 */
data class PositionAnchor(val seconds: Double, val atElapsedMs: Long)

/**
 * Queue and position kept as one, so a new item never shows the last one's time. While [seeking], [position] is
 * the target, not moving, until the server reports it there. While [resting], the app holds the queue paused at
 * [position], and a report that it plays lags behind the pause.
 */
data class QueueState(
    val queue: PlayerQueue,
    val position: PositionAnchor,
    val seeking: Boolean = false,
    val resting: Boolean = false,
) {
    val isPlaying: Boolean get() = queue.isPlaying && !resting

    /** [nowMs] is on the monotonic clock of [PositionAnchor.atElapsedMs]. */
    fun secondsAt(nowMs: Long): Double {
        if (seeking || !isPlaying) return position.seconds
        return position.seconds + (nowMs - position.atElapsedMs).coerceAtLeast(0) / MS_PER_SECOND * queue.speed
    }

    private companion object {
        const val MS_PER_SECOND = 1000.0
    }
}
