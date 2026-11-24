package io.github.hazymorning.amsel.playback

/** 15 s back is the owner's choice. */
const val SKIP_BACK_MS: Long = 15_000L
const val SKIP_FORWARD_MS: Long = 30_000L

/**
 * Chapter starts must be ascending. The server names no current chapter: it is the last one starting
 * at or before the position. Every controller goes through these, so they agree.
 */
object ChapterNavigation {
    fun indexAt(startsMs: List<Long>, positionMs: Long): Int {
        if (startsMs.isEmpty()) return -1
        return startsMs.indexOfLast { it <= positionMs }.coerceAtLeast(0)
    }

    fun previousStart(startsMs: List<Long>, positionMs: Long, restartMs: Long): Long? {
        val i = indexAt(startsMs, positionMs)
        if (i < 0) return null
        if (positionMs - startsMs[i] > restartMs) return startsMs[i]
        return startsMs.getOrNull(i - 1)
    }

    fun nextStart(startsMs: List<Long>, positionMs: Long): Long? {
        val i = indexAt(startsMs, positionMs)
        return if (i < 0) null else startsMs.getOrNull(i + 1)
    }
}
