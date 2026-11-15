package io.github.hazymorning.amsel.playback

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.data.NetworkKey
import io.github.hazymorning.amsel.data.downloads.AbsClient
import io.github.hazymorning.amsel.data.downloads.DownloadManifest
import io.github.hazymorning.amsel.data.downloads.Downloads
import io.github.hazymorning.amsel.data.downloads.LocalProgress
import java.io.IOException
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A downloaded item playing on this phone, as Now Playing shows it: where it was at [atMs] (elapsedRealtime). */
data class LocalPlaying(
    val manifest: DownloadManifest,
    val positionMs: Long,
    val atMs: Long,
    val playing: Boolean,
    val cover: Uri?,
)

/**
 * A downloaded book or episode played on this phone from its files, as Absorb and Lissen do: no
 * server in the way, so a seek or a skip is instant and needs no connection. A book's files are one
 * timeline ([ConcatenatingMediaSource2]), next and previous step by chapter, back and forward by 15
 * and 30 seconds. Where the person is stays on the phone, every few seconds and at every pause, and
 * goes to Audiobookshelf now and then ([ProgressSaves]); a start takes the newer of the two.
 */
@OptIn(UnstableApi::class)
class LocalPlayback(
    private val context: Context,
    private val downloads: Downloads,
    private val abs: AbsClient,
    /** The networks the phone is on: a send that failed waits for another. */
    network: StateFlow<NetworkKey?>,
    private val scope: CoroutineScope,
) {
    private var exo: ExoPlayer? = null
    private val saves = ProgressSaves(
        scope + Dispatchers.Main.immediate,
        network,
        ::place,
        keep = { downloads.saveProgress(it.manifest.uri, it.progress) },
        send = { (manifest, progress) ->
            abs.saveProgress(
                manifest.absItemId,
                manifest.absEpisodeId,
                progress.seconds,
                manifest.durationSec,
                progress.finished,
            )
        },
    )

    /** The cover of what [now] holds, looked up once. */
    private var cover: Uri? = null
    private val _now = MutableStateFlow<LocalPlaying?>(null)

    /** What plays from the phone's downloads; null when nothing is loaded. */
    val now: StateFlow<LocalPlaying?> = _now.asStateFlow()

    private var built: Player? = null

    /** The session's player while [now] holds something; one instance, so a swap keeps it. Main thread. */
    val player: Player get() = built ?: ChapterPlayer(exoPlayer()).also { built = it }

    /** Whether [player] is this one's, without building it to find out. */
    fun owns(player: Player): Boolean = player === built

    init {
        // Removed while it plays, here or by signing out: it stops, and nothing of it is written again.
        scope.launch(Dispatchers.Main.immediate) {
            downloads.done.collect { done ->
                if (_now.value?.manifest?.uri?.let { it !in done } == true) release()
            }
        }
    }

    /** Plays the download of [uri] from [startMs], or from where the person was. Main thread. */
    fun play(uri: String, startMs: Long? = null) {
        val manifest = downloads.manifest(uri) ?: return
        val exo = exoPlayer()
        if (_now.value?.manifest?.uri != uri) {
            cover = downloads.cover(uri)?.let(Uri::fromFile)
            exo.setMediaSource(source(manifest))
            exo.prepare()
            publish(manifest)
            scope.launch(Dispatchers.Main.immediate) {
                val at = startMs ?: resumeAt(manifest)
                if (_now.value?.manifest?.uri == uri) exo.seekTo(at)
                exo.play()
            }
        } else {
            startMs?.let(exo::seekTo)
            exo.play()
        }
    }

    fun pause() {
        exo?.pause()
    }

    /** How loud, 0 to 1, for the sleep timer's fade. Main thread. */
    fun setVolume(volume: Float) {
        exo?.volume = volume.coerceIn(0f, 1f)
    }

    /** Lets the item go, keeping where the person was: another plays instead. Main thread. */
    fun stop() {
        saves.save(send = true)
        release()
    }

    private fun release() {
        val exo = exo ?: return
        saves.stop()
        _now.value = null
        exo.stop()
        exo.clearMediaItems()
        exo.volume = 1f
    }

    private fun exoPlayer(): ExoPlayer = exo ?: ExoPlayer.Builder(context)
        .setLooper(Looper.getMainLooper())
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .setSeekBackIncrementMs(SKIP_BACK_MS)
        .setSeekForwardIncrementMs(SKIP_FORWARD_MS)
        .build()
        .also { player ->
            exo = player
            player.addListener(
                object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        _now.value?.manifest?.let(::publish)
                        if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) saves.playing(player.isPlaying)
                    }
                },
            )
        }

    private fun source(manifest: DownloadManifest): ConcatenatingMediaSource2 {
        val files = ProgressiveMediaSource.Factory(FileDataSource.Factory())
        val item = MediaItem.Builder()
            .setMediaId(manifest.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(manifest.title)
                    .setArtist(manifest.author)
                    .setArtworkUri(cover)
                    .setDurationMs((manifest.durationSec * MS).roundToLong())
                    .setMediaType(
                        if (manifest.mediaType == MediaType.PodcastEpisode.api) {
                            MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
                        } else {
                            MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
                        },
                    )
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
        val builder = ConcatenatingMediaSource2.Builder().setMediaItem(item)
        for (file in manifest.files) {
            val uri = Uri.fromFile(downloads.file(manifest.uri, file))
            // ExoPlayer takes a file's length up front, to lay out the timeline before it reads the file.
            val length = (file.durationSec * MS).roundToLong().coerceAtLeast(1)
            builder.add(files.createMediaSource(MediaItem.fromUri(uri)), length)
        }
        return builder.build()
    }

    private fun publish(manifest: DownloadManifest) {
        val exo = exo ?: return
        _now.value = LocalPlaying(
            manifest,
            exo.currentPosition,
            SystemClock.elapsedRealtime(),
            exo.isPlaying,
            cover,
        )
    }

    /** The newer of this phone's place and Audiobookshelf's, in ms; the start when it was finished. */
    private suspend fun resumeAt(manifest: DownloadManifest): Long {
        val local = withContext(Dispatchers.IO) { downloads.progress(manifest.uri) }
        val remote = withContext(Dispatchers.IO) {
            withTimeoutOrNull(REMOTE_WAIT_MS) {
                try {
                    abs.progress(manifest.absItemId, manifest.absEpisodeId)
                } catch (e: IOException) {
                    null
                }
            }
        }
        val (seconds, finished) = when {
            remote != null && remote.lastUpdate > (local?.updatedAt ?: 0) -> remote.currentTime to remote.isFinished
            local != null -> local.seconds to local.finished
            else -> 0.0 to false
        }
        return if (finished) 0 else (seconds * MS).roundToLong()
    }

    /** Where the person is in what is loaded. Main thread. */
    private fun place(): DownloadPlace? {
        val exo = exo ?: return null
        val manifest = _now.value?.manifest ?: return null
        val finished = exo.playbackState == Player.STATE_ENDED
        return DownloadPlace(manifest, LocalProgress(exo.currentPosition / MS, System.currentTimeMillis(), finished))
    }

    /** Next and previous step through the chapters, as for a speaker ([Chapters]). */
    private inner class ChapterPlayer(player: ExoPlayer) : ForwardingPlayer(player) {
        private val starts: List<Long>
            get() = _now.value?.manifest?.chapters.orEmpty().map { (it.startSec * MS).roundToLong() }.sorted()

        override fun getAvailableCommands(): Player.Commands {
            val commands = super.getAvailableCommands().buildUpon()
            if (starts.size >= 2) commands.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS)
            return commands.build()
        }

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        override fun seekToNext() {
            Chapters.nextStart(starts, currentPosition)?.let(::seekTo)
        }

        override fun seekToPrevious() {
            Chapters.previousStart(starts, currentPosition, RemotePlayer.MA_RESTART_THRESHOLD_MS)?.let(::seekTo)
                ?: seekTo(0)
        }
    }

    private companion object {
        const val MS = 1000.0
        const val REMOTE_WAIT_MS = 3_000L
    }
}
