package io.github.hazymorning.amsel.app

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.formatDuration
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.model.PlayerControl
import io.github.hazymorning.amsel.ui.model.positionAt
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** [chapter] counts from 1, as the chapter list shows it. */
@Immutable
@Serializable
data class Bookmark(
    val id: Long,
    val itemKey: String,
    val itemTitle: String,
    val positionMs: Long,
    val createdAtMs: Long,
    val parentKey: String? = null,
    val chapter: Int? = null,
    val note: String = "",
)

// On the phone: no released server keeps bookmarks yet (docs/API-NOTES.md, "Audiobooks and podcasts").
class BookmarkStore(
    private val context: Context,
    private val playing: PlaybackState,
    private val playback: PlaybackController,
    private val messages: MessageController,
    private val scope: CoroutineScope,
) {
    private val store = JsonListStore(File(context.filesDir, "bookmarks.json"), Bookmark.serializer(), scope)

    /** Newest first. */
    val all: StateFlow<List<Bookmark>> = store.items

    fun add(bookmark: Bookmark) = store.change { all -> (all + bookmark).sortedByDescending(Bookmark::createdAtMs) }

    fun addHere() {
        val now = playing.nowPlaying.value?.takeIf { it.spoken } ?: return
        val key = now.key ?: return
        val position = now.positionAt(SystemClock.elapsedRealtime())
        val chapter = if (now.hasChapters) now.chapters.lastOrNull { it.startMs <= position }?.number else null
        val made = System.currentTimeMillis()
        val bookmark = Bookmark(made, key, now.title, position, made, now.parentKey, chapter)
        add(bookmark)
        val where = listOfNotNull(
            chapter?.let { context.getString(R.string.bookmark_chapter, it) },
            formatDuration((position / MS).toInt()),
        ).joinToString(DOT_SEPARATOR)
        messages.showUndo(R.string.bookmark_saved, where) { remove(bookmark.id) }
    }

    fun play(bookmark: Bookmark) {
        val now = playing.nowPlaying.value
        if (now?.key == bookmark.itemKey) {
            playback.control(PlayerControl.Seek(bookmark.positionMs))
            if (!now.isPlaying) playback.control(PlayerControl.PlayPause)
            return
        }
        playback.play(listOf(bookmark.itemKey), title = bookmark.itemTitle, startPositionMs = bookmark.positionMs)
    }

    fun delete(bookmark: Bookmark) {
        remove(bookmark.id)
        messages.showUndo(R.string.bookmark_deleted) { add(bookmark) }
    }

    fun remove(id: Long) = store.change { all -> all.filterNot { it.id == id } }

    fun clear() = store.change { emptyList() }

    fun setNote(id: Long, note: String) = store.change { all ->
        all.map { if (it.id == id) it.copy(note = note.trim()) else it }
    }

    private companion object {
        const val MS = 1000L
    }
}
