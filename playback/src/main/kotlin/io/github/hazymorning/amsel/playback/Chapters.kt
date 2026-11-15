package io.github.hazymorning.amsel.playback

/** How far back and forward a book or an episode skips: 15 s back (the owner's choice), 30 s forward. */
const val SKIP_BACK_MS: Long = 15_000L
const val SKIP_FORWARD_MS: Long = 30_000L

/**
 * Chapter navigation on a book's or episode's own timeline, by the chapters' starts in ascending
 * order. Music Assistant names no current chapter: as in its web UI, it is the last one whose start
 * is at or before the position, and the last chapter holds the end. The app, the notification, a
 * headset and the car all go through these, so they agree.
 */
object Chapters {
    /** The chapter holding [positionMs]: its index in [startsMs], or -1 without chapters. */
    fun indexAt(startsMs: List<Long>, positionMs: Long): Int {
        if (startsMs.isEmpty()) return -1
        return startsMs.indexOfLast { it <= positionMs }.coerceAtLeast(0)
    }

    /**
     * Where previous goes: back to the current chapter's start once more than [restartMs] into it,
     * as a song restarts, else to the previous chapter's start. Null in the first chapter's
     * opening seconds, where the queue's previous item takes over.
     */
    fun previousStart(startsMs: List<Long>, positionMs: Long, restartMs: Long): Long? {
        val i = indexAt(startsMs, positionMs)
        if (i < 0) return null
        if (positionMs - startsMs[i] > restartMs) return startsMs[i]
        return startsMs.getOrNull(i - 1)
    }

    /** Where next goes: the next chapter's start; null in the last chapter, where the queue's next item takes over. */
    fun nextStart(startsMs: List<Long>, positionMs: Long): Long? {
        val i = indexAt(startsMs, positionMs)
        return if (i < 0) null else startsMs.getOrNull(i + 1)
    }
}
