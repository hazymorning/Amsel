package io.github.hazymorning.amsel.ui.mapping

import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.startItemFor
import io.github.hazymorning.amsel.ui.model.ChapterUi
import kotlin.math.roundToLong

/**
 * A book's or episode's chapters in order. A chapter without an end of its own ends where the next
 * starts, the last one with the item. Each names what starts the book there, which after a merge of
 * chapter lists may not be its own position ([startItemFor]).
 */
fun chapterList(item: MediaItem): List<ChapterUi> {
    val sorted = item.chapters.sortedBy { it.start }
    val totalMs = item.duration?.takeIf { it > 0 }?.let { it * 1000L }
    return sorted.mapIndexed { i, chapter ->
        val startMs = (chapter.start * 1000).roundToLong()
        val nextMs = sorted.getOrNull(i + 1)?.let { (it.start * 1000).roundToLong() }
        val endMs = chapter.end?.let { (it * 1000).roundToLong() }?.takeIf { it > startMs }
            ?: nextMs ?: totalMs ?: startMs
        ChapterUi(i + 1, chapter.name.trim(), startMs, endMs, item.startItemFor(chapter))
    }
}
