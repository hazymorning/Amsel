package io.github.hazymorning.amsel.ui.mapping

import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.startItemFor
import io.github.hazymorning.amsel.ui.model.ChapterUi
import kotlin.math.roundToLong

/**
 * Each chapter names the start item that plays it ([startItemFor]): after a merge of chapter lists its
 * own position may start another. See docs/API-NOTES.md, "start_item".
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
