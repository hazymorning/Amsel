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

@Serializable
data class CachedPage(
    val item: MediaItem,
    /** An album's or playlist's tracks, an artist's popular songs, a podcast's newest episodes. */
    val rows: List<MediaItem> = emptyList(),
    /** An artist's albums. */
    val shelf: List<MediaItem> = emptyList(),
)

class PageCache(private val dir: File, private val serverId: suspend () -> String?) {
    suspend fun read(uri: String): CachedPage? {
        val file = file(uri) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                MaJson.decodeFromString(CachedPage.serializer(), file.readText()).also {
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

    suspend fun write(uri: String, page: CachedPage) {
        val file = file(uri) ?: return
        val kept = page.copy(rows = page.rows.take(ROWS), shelf = page.shelf.take(ROWS))
        withContext(Dispatchers.IO) {
            try {
                val folder = file.parentFile ?: return@withContext
                folder.mkdirs()
                val next = File(folder, file.name + ".new")
                next.writeText(MaJson.encodeToString(CachedPage.serializer(), kept))
                if (!next.renameTo(file)) next.delete()
                folder.listFiles()?.takeIf { it.size > PAGES }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(PAGES)
                    ?.forEach { it.delete() }
            } catch (e: IOException) {
                // Not cached: the next visit loads from the server.
            }
        }
    }

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
        // A few screens' worth; the rest loads from the server below them.
        const val ROWS = 100
        const val PAGES = 200
        private const val NAME_BYTES = 12
    }
}
