package io.github.hazymorning.amsel.data.downloads

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * One item's download: what Audiobookshelf holds for it, then each audio file in turn. A file
 * grows in `<name>.part` and is renamed once it has every byte; a part left by a lost connection
 * or a restart goes on from where it ended (HTTP Range), so nothing is fetched twice.
 */
internal class DownloadJob(private val abs: AbsClient, private val store: DownloadStore) {
    /** [stub] with its files, chapters and length, as the server has them now. */
    suspend fun plan(stub: DownloadManifest): DownloadManifest {
        val media = abs.item(stub.absItemId).media
        val episode = stub.absEpisodeId?.let { id ->
            media.episodes.firstOrNull { it.id == id } ?: throw AbsException(AbsException.Reason.NotFound)
        }
        val tracks = if (episode != null) listOfNotNull(episode.audioTrack) else media.tracks
        if (tracks.isEmpty()) throw AbsException(AbsException.Reason.NotFound)
        val files = tracks.mapIndexed { i, track ->
            // The server's word becomes part of a path on the phone: letters and digits only.
            val ext = track.metadata?.ext?.trimStart('.')?.takeIf { it.matches(EXT) } ?: "audio"
            DownloadFile(
                track.ino,
                "%03d.%s".format(i + 1, ext),
                track.metadata?.size ?: 0,
                track.startOffset,
                track.duration,
            )
        }
        val chapters = (episode?.chapters ?: media.chapters).map { DownloadChapter(it.title, it.start, it.end) }
        return stub.copy(
            title = episode?.title ?: media.metadata.title ?: stub.title,
            author = stub.author ?: media.metadata.authorName ?: media.metadata.author,
            durationSec = files.sumOf { it.durationSec }.takeIf { it > 0 } ?: media.duration,
            files = files,
            chapters = chapters,
        )
    }

    /** The cover, when the server has one; a book plays without it. */
    // Both read their answer, so off the main thread (see AbsClient.call).
    suspend fun cover(manifest: DownloadManifest): Boolean = withContext(Dispatchers.IO) { coverHere(manifest) }

    private suspend fun coverHere(manifest: DownloadManifest): Boolean {
        val target = store.cover(manifest.uri)
        if (target.isFile) return true
        return try {
            abs.cover(manifest.absItemId).use { response ->
                if (!response.isSuccessful) return false
                val part = File(target.path + PART)
                copy(response.body.byteStream(), part, append = false) { }
                part.renameTo(target)
            }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Fetches [file] of [manifest] unless it is already in place. [onProgress] hears how many of its
     * bytes are on the phone, as they arrive.
     */
    suspend fun fetch(manifest: DownloadManifest, file: DownloadFile, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) { fetchHere(manifest, file, onProgress) }

    private suspend fun fetchHere(manifest: DownloadManifest, file: DownloadFile, onProgress: (Long) -> Unit) {
        val target = store.file(manifest.uri, file)
        if (target.isFile && (file.size == 0L || target.length() == file.size)) return onProgress(target.length())
        val part = File(target.path + PART)
        if (file.size > 0 && part.length() > file.size) part.delete()
        val from = part.length()
        abs.file(manifest.absItemId, file.ino, from).use { response ->
            val append = when (response.code) {
                HTTP_PARTIAL -> true

                // The server sent the whole file: the part starts again.
                HTTP_OK -> false

                // Already all there: the last attempt ended just before the rename.
                HTTP_RANGE_NOT_SATISFIABLE -> if (from == file.size) return finish(part, target) else false

                // The token was renewed on the way here: the server refuses this file, not the account.
                401, 403 -> throw AbsException(AbsException.Reason.Refused)

                404 -> throw AbsException(AbsException.Reason.NotFound)

                else -> throw AbsException(AbsException.Reason.Server)
            }
            copy(response.body.byteStream(), part, append, onProgress)
        }
        if (file.size > 0 && part.length() != file.size) throw IOException("${file.name} came short")
        finish(part, target)
    }

    private fun finish(part: File, target: File) {
        if (!part.renameTo(target)) throw IOException("Could not keep ${target.name}")
    }

    private suspend fun copy(input: InputStream, part: File, append: Boolean, onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            part.parentFile?.mkdirs()
            var written = if (append) part.length() else 0L
            input.use {
                FileOutputStream(part, append).use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        onProgress(written)
                    }
                }
            }
        }

    companion object {
        const val PART = ".part"
        private val EXT = Regex("[A-Za-z0-9]{1,10}")
        private const val BUFFER = 64 * 1024
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
