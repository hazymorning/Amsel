package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.MaJson
import io.github.hazymorning.amsel.api.MediaItem
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** What a page last showed: its item and lists as the server sent them, the first [PageMemory.ROWS] of each. */
@Serializable
data class ShownPage(
    val item: MediaItem,
    /** An album's or playlist's tracks, an artist's popular songs, a podcast's newest episodes. */
    val rows: List<MediaItem> = emptyList(),
    /** An artist's albums. */
    val shelf: List<MediaItem> = emptyList(),
)

/**
 * The pages last shown on this phone, so the next visit paints at once while the server is asked
 * again: a file per server and item uri, the least recently shown going past [PAGES]. A cache:
 * Android may clear it, and a page it no longer holds loads as it always did.
 */
class PageMemory(private val dir: File, private val serverId: suspend () -> String?) {
    suspend fun read(uri: String): ShownPage? {
        val file = file(uri) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                MaJson.decodeFromString(ShownPage.serializer(), file.readText()).also {
                    file.setLastModified(System.currentTimeMillis())
                }
            } catch (e: IOException) {
                null
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

    suspend fun write(uri: String, page: ShownPage) {
        val file = file(uri) ?: return
        val kept = page.copy(rows = page.rows.take(ROWS), shelf = page.shelf.take(ROWS))
        withContext(Dispatchers.IO) {
            try {
                val folder = file.parentFile ?: return@withContext
                folder.mkdirs()
                val next = File(folder, file.name + ".new")
                next.writeText(MaJson.encodeToString(ShownPage.serializer(), kept))
                if (!next.renameTo(file)) next.delete()
                folder.listFiles()?.takeIf { it.size > PAGES }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(PAGES)
                    ?.forEach { it.delete() }
            } catch (e: IOException) {
                // Not kept: the next visit loads from the server, as before.
            }
        }
    }

    /** Forgets every page, as the server's other data is forgotten on signing out. */
    suspend fun clear() {
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
    }

    private suspend fun file(uri: String): File? {
        val server = serverId() ?: return null
        return File(File(dir, name(server)), name(uri) + ".json")
    }

    private fun name(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(NAME_BYTES).joinToString("") {
            "%02x".format(it)
        }

    companion object {
        /** A few screens: the rest load under them as before. */
        const val ROWS = 100
        const val PAGES = 200
        private const val NAME_BYTES = 12
    }
}
