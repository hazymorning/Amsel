package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.PlayerQueue

data class ItemPosition(val uri: String, val seconds: Double)

/** [playing] includes about to play; [starting] while a play action runs (`play_action_in_progress`). */
data class CurrentItemStatus(val uri: String, val playing: Boolean, val starting: Boolean = false)

/**
 * Told before a command goes out, so every screen, the notification and Android Auto show the target at once:
 * until the new stream runs, the server reports the old stream's time on top of the target, or 0. A call with
 * a ticket older than the queue's latest is ignored. See docs/API-NOTES.md, "Position after a seek or a play".
 */
interface SeekTracker {
    /** Null when the queue is empty, unknown, or not its speaker's source. */
    fun currentItemStatus(queueId: String): CurrentItemStatus?

    /** Shows [queueId] at [place], not moving, until the server reports it there; returns the ticket. */
    fun startSeek(queueId: String, place: ItemPosition): Long

    fun sent(queueId: String, ticket: Long)

    /** The server answers once the new stream runs; its reports count again from then. */
    fun answered(queueId: String, ticket: Long)

    /** Also when the command will not be sent; the queue shows the server's position again. */
    fun failed(queueId: String, ticket: Long)

    /** For a queue that does not play or is being paused: shows it at [place], and its next play starts there. */
    fun setPausedPosition(queueId: String, place: ItemPosition)

    /** Null where the server's own position is right. */
    fun pausedPosition(queueId: String): ItemPosition?

    /** Returns at once when no play action runs on [queueId], else when it ends. */
    suspend fun awaitStarted(queueId: String)

    companion object {
        val None: SeekTracker = object : SeekTracker {
            override fun currentItemStatus(queueId: String): CurrentItemStatus? = null

            override fun startSeek(queueId: String, place: ItemPosition): Long = 0

            override fun sent(queueId: String, ticket: Long) = Unit

            override fun answered(queueId: String, ticket: Long) = Unit

            override fun failed(queueId: String, ticket: Long) = Unit

            override fun setPausedPosition(queueId: String, place: ItemPosition) = Unit

            override fun pausedPosition(queueId: String): ItemPosition? = null

            override suspend fun awaitStarted(queueId: String) = Unit
        }
    }
}

internal sealed interface PendingSeek {
    val place: ItemPosition

    /** The state to show for [reported], and this seek as it goes on, or null once it is over. */
    fun shows(queue: PlayerQueue, reported: PositionAnchor, nowMs: Long): Pair<QueueState, PendingSeek?>

    /**
     * Shown at [place]; the next play is a seek there, until the queue plays or holds another item. Right after a
     * pause the server still reports playing, so a playing report ends this only once [rested] or after [PAUSE_MS].
     */
    data class Paused(override val place: ItemPosition, val atMs: Long, val rested: Boolean) : PendingSeek {
        /** Whether a playing report means the queue left [place]: started again, or the pause never took. */
        fun left(reportAtMs: Long): Boolean = rested || reportAtMs - atMs > PAUSE_MS

        override fun shows(queue: PlayerQueue, reported: PositionAnchor, nowMs: Long): Pair<QueueState, PendingSeek?> {
            val gone = queue.currentUri != place.uri || (queue.isPlaying && left(reported.atElapsedMs))
            if (gone) return QueueState(queue, reported) to null
            val here = PositionAnchor(place.seconds, reported.atElapsedMs)
            return QueueState(queue, here, resting = true) to copy(rested = rested || !queue.isPlaying)
        }
    }

    /** A seek, or a play that starts at [place], not yet confirmed by the server. */
    data class Seeking(
        override val place: ItemPosition,
        val ticket: Long,
        val askedAtMs: Long,
        /** The queue has held [place]'s item since this began; holding another after that ends the seek. */
        val met: Boolean,
        /** The server's position before, shown again if the command fails. */
        val before: PositionAnchor?,
        val sentAtMs: Long? = null,
        val answeredAtMs: Long? = null,
        /** The latest report: checked when the command is answered, shown when the seek times out. */
        val reported: PositionAnchor? = null,
        /** Set when the new stream starts short of the target, which shows until the stream reaches it. */
        val arriving: PositionAnchor? = null,
    ) : PendingSeek {
        /**
         * At the target, or past it by at most the time since the command went out (at [speed], with margins):
         * the new stream cannot have played longer, while the old stream's time the server adds on top has.
         */
        fun fits(report: PositionAnchor, speed: Double): Boolean {
            val since = (report.atElapsedMs - (sentAtMs ?: askedAtMs)).coerceAtLeast(0) / MS_PER_SECOND * speed
            return report.seconds >= place.seconds - EARLY_S && report.seconds <= place.seconds + since + LATE_S
        }

        /** Never before the answer, which comes only once the new stream runs and can take long. */
        fun over(nowMs: Long): Boolean = answeredAtMs != null && nowMs - answeredAtMs > WINDOW_MS

        /**
         * True from [SETTLE_MS] after the answer: until then a report can be the old stream's time on top of the
         * target, which fits where the old stream was young. See docs/API-NOTES.md, "Position after a seek or a play".
         */
        fun settled(nowMs: Long): Boolean = answeredAtMs != null && nowMs - answeredAtMs >= SETTLE_MS

        /**
         * Holds [report] at the target until the stream reaches it: the server starts a chapter 0.5 s early,
         * which would briefly show the chapter before. See docs/API-NOTES.md, "`start_item`".
         */
        fun notBefore(report: PositionAnchor, speed: Double): PositionAnchor {
            if (report.seconds >= place.seconds) return report
            val catchUpMs = ((place.seconds - report.seconds) / speed * MS_PER_SECOND).toLong()
            return PositionAnchor(place.seconds, report.atElapsedMs + catchUpMs)
        }

        override fun shows(queue: PlayerQueue, reported: PositionAnchor, nowMs: Long): Pair<QueueState, PendingSeek?> {
            val servers = QueueState(queue, reported)
            // Another item: not there yet (a book started at a chapter) or, once [met], moved on.
            if (queue.currentUri != place.uri) return servers to takeUnless { met }
            if (over(nowMs)) return servers to null
            val counts = settled(nowMs) && !queue.playActionInProgress && fits(reported, queue.speed)
            if (!counts) {
                val standing = PositionAnchor(place.seconds, reported.atElapsedMs)
                val state = QueueState(queue, arriving ?: standing, seeking = arriving == null)
                return state to copy(met = true, reported = reported)
            }
            if (reported.seconds >= place.seconds) return servers to null
            val early = notBefore(reported, queue.speed)
            return QueueState(queue, early) to copy(met = true, reported = reported, arriving = early)
        }
    }

    companion object {
        // After the answer, the server's report counts whatever it is once this has passed.
        // See docs/API-NOTES.md, "Seek".
        const val WINDOW_MS = 30_000L

        // How long a pause may take to show in the server's reports.
        const val PAUSE_MS = 5_000L

        // After the answer, reports are only kept this long; then the latest counts ([Seeking.settled]).
        const val SETTLE_MS = 1_000L

        // The server cuts seeks to whole seconds and starts a chapter 0.5 s early.
        const val EARLY_S = 2.0

        // A speaker may report a little ahead of what is heard.
        const val LATE_S = 3.0
        private const val MS_PER_SECOND = 1000.0
    }
}
