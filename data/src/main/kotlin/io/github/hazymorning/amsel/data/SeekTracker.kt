package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.PlayerQueue

/** A book, an episode or a song, and where in it. */
data class ItemPosition(val uri: String, val seconds: Double)

/** What a queue holds, and whether it plays or is about to; [starting] while the server starts a play there. */
data class CurrentItemStatus(val uri: String, val playing: Boolean, val starting: Boolean = false)

/**
 * Where the app puts a speaker's queue, told before the command goes out, so every screen, the
 * notification and the car show the same place at once and step on from it. Music Assistant
 * restarts the stream for a seek and starts playing, whatever the queue did before, and until the
 * new stream runs it reports the old stream's time added to the target, or 0 (2.10.9 and 2.11.0b7,
 * live): such a report is not where the item plays. [startSeek] hands out a ticket; what is told with an
 * older one than the queue's latest is ignored.
 */
interface SeekTracker {
    /** What [queueId] holds; null when it holds nothing, is not known, or is not what its speaker plays. */
    fun currentItemStatus(queueId: String): CurrentItemStatus?

    /** [queueId] is on its way to [place]: it shows there, standing still, until the server reports it there. */
    fun startSeek(queueId: String, place: ItemPosition): Long

    /** The command went out. */
    fun sent(queueId: String, ticket: Long)

    /** The server answered it, which it does once the new stream runs: its reports count again. */
    fun answered(queueId: String, ticket: Long)

    /** The command did not reach the server, or will not go out: the queue is where the server has it. */
    fun failed(queueId: String, ticket: Long)

    /** [queueId] does not play, or is being paused: it shows at [place], and its next play starts there. */
    fun setPausedPosition(queueId: String, place: ItemPosition)

    /** Where a play must start [queueId]; null where the server's own place is right. */
    fun pausedPosition(queueId: String): ItemPosition?

    /** Returns once the server is not starting a play on [queueId], or no longer is. */
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

/** Where the app put a queue's item, while the server does not show it there. */
internal sealed interface PendingSeek {
    val place: ItemPosition

    /**
     * What [queue] shows for the position [reported] for it, weighed at [nowMs], and what is left
     * of this seek after it: null once it is over.
     */
    fun shows(queue: PlayerQueue, reported: PositionAnchor, nowMs: Long): Pair<QueueState, PendingSeek?>

    /**
     * The queue does not play: it shows at [place], and the next play is a seek there. Its own time
     * counts again once it plays or holds something else. Parked with a pause still on its way, the
     * server reports it playing a moment longer: only once it has [rested] does playing mean that
     * someone started it again.
     */
    data class Paused(override val place: ItemPosition, val atMs: Long, val rested: Boolean) : PendingSeek {
        /** A queue reported playing has left this place: started again elsewhere, or never paused. */
        fun left(reportAtMs: Long): Boolean = rested || reportAtMs - atMs > PAUSE_MS

        override fun shows(queue: PlayerQueue, reported: PositionAnchor, nowMs: Long): Pair<QueueState, PendingSeek?> {
            val gone = queue.currentUri != place.uri || (queue.isPlaying && left(reported.atElapsedMs))
            if (gone) return QueueState(queue, reported) to null
            val here = PositionAnchor(place.seconds, reported.atElapsedMs)
            return QueueState(queue, here, resting = true) to copy(rested = rested || !queue.isPlaying)
        }
    }

    /** A seek, or a play that starts there, on its way. */
    data class Seeking(
        override val place: ItemPosition,
        val ticket: Long,
        val askedAtMs: Long,
        /** The queue held [place]'s item since this began; one that then holds another has left it. */
        val met: Boolean,
        /** Where the server had the queue before, should the command fail. */
        val before: PositionAnchor?,
        val sentAtMs: Long? = null,
        val answeredAtMs: Long? = null,
        /** The server's latest word: weighed when the command is answered, shown when this runs out. */
        val reported: PositionAnchor? = null,
        /** The new stream runs but starts a moment early: shown at the target until it gets there. */
        val arriving: PositionAnchor? = null,
    ) : PendingSeek {
        /**
         * At the target, or past it by no more than the time since the command went out at the
         * queue's [speed], give or take: the new stream cannot have played longer than that, while
         * the old stream's time, which the server adds to the target, has.
         */
        fun fits(report: PositionAnchor, speed: Double): Boolean {
            val since = (report.atElapsedMs - (sentAtMs ?: askedAtMs)).coerceAtLeast(0) / MS_PER_SECOND * speed
            return report.seconds >= place.seconds - EARLY_S && report.seconds <= place.seconds + since + LATE_S
        }

        /** Never while the command is on its way: the server answers once the new stream runs, in minutes at worst. */
        fun over(nowMs: Long): Boolean = answeredAtMs != null && nowMs - answeredAtMs > WINDOW_MS

        /**
         * The server has had its moment after the answer. Its first word then is still the old
         * stream's time on top of the target, corrected a third of a second later (live): where the
         * old stream was young, that passes for the target, so no report ends the seek before this.
         */
        fun settled(nowMs: Long): Boolean = answeredAtMs != null && nowMs - answeredAtMs >= SETTLE_MS

        /**
         * [report], held at the target until the stream reaches it: the server starts a book at a
         * chapter half a second early, which would show the chapter before for a moment.
         */
        fun notBefore(report: PositionAnchor, speed: Double): PositionAnchor {
            if (report.seconds >= place.seconds) return report
            val catchUpMs = ((place.seconds - report.seconds) / speed * MS_PER_SECOND).toLong()
            return PositionAnchor(place.seconds, report.atElapsedMs + catchUpMs)
        }

        /**
         * A report counts once the server has answered the command, had its moment after that and
         * finished the play action, and the report fits the target; until then the queue stands at
         * the target. One that fits just short of the target shows the target until the stream is
         * there.
         */
        override fun shows(queue: PlayerQueue, reported: PositionAnchor, nowMs: Long): Pair<QueueState, PendingSeek?> {
            val servers = QueueState(queue, reported)
            // Not there yet (a book started at a chapter), or the queue has moved on: its time is its own.
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
        /**
         * How long after its answer the server may take to report the target; then its word counts
         * whatever it is. A stream from Audiobookshelf over a VPN took most of this (household test).
         */
        const val WINDOW_MS = 30_000L

        /** How long a pause may take to show in the server's reports. */
        const val PAUSE_MS = 5_000L

        /** How long after its answer the server's reports are only kept, the latest to count ([Seeking.settled]). */
        const val SETTLE_MS = 1_000L

        /** Seeks are cut to whole seconds, and a chapter starts half a second early. */
        const val EARLY_S = 2.0

        /** A speaker may report a little ahead of what is heard. */
        const val LATE_S = 3.0
        private const val MS_PER_SECOND = 1000.0
    }
}
