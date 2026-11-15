package io.github.hazymorning.amsel.data.downloads

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * A downloaded (or downloading) book or episode: where it comes from, its audio files in playing
 * order on one timeline, its chapters and cover. [uri] is Music Assistant's (`library://audiobook/3`).
 */
@Serializable
data class DownloadManifest(
    val uri: String,
    val absItemId: String,
    val absEpisodeId: String? = null,
    val title: String,
    val author: String? = null,
    /** "audiobook" or "podcast_episode". */
    val mediaType: String,
    val durationSec: Double = 0.0,
    val files: List<DownloadFile> = emptyList(),
    val chapters: List<DownloadChapter> = emptyList(),
    val hasCover: Boolean = false,
    /** Every file is in place; until then the download goes on, also after a restart. */
    val complete: Boolean = false,
    val addedAt: Long = 0,
) {
    val bytes: Long get() = files.sumOf { it.size }
}

@Serializable
data class DownloadFile(
    val ino: String,
    val name: String,
    val size: Long,
    val startSec: Double,
    val durationSec: Double,
)

@Serializable
data class DownloadChapter(val title: String, val startSec: Double, val endSec: Double)

/** Where the person stopped in a download, on this phone; [updatedAt] in epoch ms. */
@Serializable
data class LocalProgress(val seconds: Double, val updatedAt: Long, val finished: Boolean = false)

/**
 * One folder per item under [root] (no backup: a book is hundreds of megabytes): its manifest, its
 * progress, its cover and its audio files. Every write goes to a temporary file first and is then
 * renamed, so a write cut short leaves the last good one.
 */
class DownloadStore(private val root: File) {
    fun dir(uri: String): File = root.resolve(key(uri))

    fun all(): List<DownloadManifest> =
        root.listFiles().orEmpty().mapNotNull { read(it.resolve(MANIFEST), DownloadManifest.serializer()) }

    fun manifest(uri: String): DownloadManifest? = read(dir(uri).resolve(MANIFEST), DownloadManifest.serializer())

    fun save(manifest: DownloadManifest) =
        write(dir(manifest.uri).resolve(MANIFEST), manifest, DownloadManifest.serializer())

    fun progress(uri: String): LocalProgress? = read(dir(uri).resolve(PROGRESS), LocalProgress.serializer())

    fun saveProgress(uri: String, progress: LocalProgress) {
        if (dir(uri).isDirectory) write(dir(uri).resolve(PROGRESS), progress, LocalProgress.serializer())
    }

    fun file(uri: String, file: DownloadFile): File = dir(uri).resolve(file.name)

    fun cover(uri: String): File = dir(uri).resolve(COVER)

    fun remove(uri: String) {
        dir(uri).deleteRecursively()
    }

    /** Bytes the downloads take on this phone. */
    fun used(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun <T> read(file: File, serializer: KSerializer<T>): T? = try {
        if (file.isFile) AbsJson.decodeFromString(serializer, file.readText()) else null
    } catch (e: IOException) {
        null
    } catch (e: SerializationException) {
        null
    }

    private fun <T> write(file: File, value: T, serializer: KSerializer<T>) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + TEMP)
        temp.writeText(AbsJson.encodeToString(serializer, value))
        if (!temp.renameTo(file)) throw IOException("Could not save ${file.name}")
    }

    companion object {
        private const val MANIFEST = "manifest.json"
        private const val PROGRESS = "progress.json"
        private const val COVER = "cover"
        private const val TEMP = ".tmp"
        private const val KEY_CHARS = 16

        /** A folder name for [uri]: the uri itself holds slashes and colons. */
        internal fun key(uri: String): String = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(KEY_CHARS)
    }
}
