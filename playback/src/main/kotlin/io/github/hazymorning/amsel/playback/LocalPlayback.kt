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
import io.github.hazymorning.amsel.data.downloads.DownloadRepository
import io.github.hazymorning.amsel.data.downloads.LocalProgress
import java.io.IOException
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** [positionMs] as of [atMs] on the elapsedRealtime clock. */
data class LocalPlaybackState(
    val manifest: DownloadManifest,
    val positionMs: Long,
    val atMs: Long,
    val playing: Boolean,
    val cover: Uri?,
)

/** Main thread only (the player's looper), except [stopDownloadPlayback]. */
@OptIn(UnstableApi::class)
class LocalPlayback(
    private val context: Context,
    private val downloads: DownloadRepository,
    private val abs: AbsClient,
    network: StateFlow<NetworkKey?>,
    private val scope: CoroutineScope,
) {
    private var exo: ExoPlayer? = null
    private val saves = DownloadProgressSync(
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

    private var cover: Uri? = null

    /** Looks up where the loaded item starts; until then its place is unknown and nothing of it is saved. */
    private var resuming: Job? = null

    /** Whether the loaded item plays once [resuming] is done; a pause meanwhile clears it. */
    private var startWhenResumed = false
    private val _now = MutableStateFlow<LocalPlaybackState?>(null)

    val now: StateFlow<LocalPlaybackState?> = _now.asStateFlow()

    private var built: Player? = null

    /** One instance, so the session can swap it out and back without losing the item. */
    val player: Player get() = built ?: ChapterPlayer(exoPlayer()).also { built = it }

    /** Whether [player] is this one's, without building it to find out. */
    fun owns(player: Player): Boolean = player === built

    init {
        // A download removed while it plays, also by signing out, stops; its place is not written again.
        scope.launch(Dispatchers.Main.immediate) {
            downloads.done.collect { done ->
                if (_now.value?.manifest?.uri?.let { it !in done } == true) release()
            }
        }
    }

    /** [startMs] null resumes from the newer of this phone's and Audiobookshelf's place. */
    fun play(uri: String, startMs: Long? = null) {
        val manifest = downloads.manifest(uri) ?: return
        val exo = exoPlayer()
        if (_now.value?.manifest?.uri != uri) {
            val art = downloads.cover(uri)?.let(Uri::fromFile)
            val source = try {
                source(manifest, art)
            } catch (e: IOException) {
                // A manifest saved before file names were checked may name a file outside its folder.
                return
            }
            // The previous item's place is saved before the new one replaces it.
            if (_now.value != null) {
                saves.save(send = true)
                saves.stop()
            }
            resuming?.cancel()
            cover = art
            // Paused while its place is looked up, or the previous item's playWhenReady would start it at 0.
            exo.playWhenReady = false
            exo.setMediaSource(source)
            exo.prepare()
            publish(manifest)
            startWhenResumed = true
            resuming = scope.launch(Dispatchers.Main.immediate) {
                exo.seekTo(startMs ?: resumeAt(manifest))
                resuming = null
                if (startWhenResumed) exo.play()
            }
        } else if (resuming != null && startMs == null) {
            startWhenResumed = true
        } else {
            resuming?.cancel()
            resuming = null
            startMs?.let(exo::seekTo)
            exo.play()
        }
    }

    fun pause() {
        startWhenResumed = false
        exo?.pause()
    }

    fun setVolume(volume: Float) {
        exo?.volume = volume.coerceIn(0f, 1f)
    }

    fun stop() {
        saves.save(send = true)
        release()
    }

    fun stopDownloadPlayback() {
        if (now.value != null) scope.launch(Dispatchers.Main) { stop() }
    }

    private fun release() {
        resuming?.cancel()
        resuming = null
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
        // Media3 would report each playback to Android's media metrics; Amsel reports nothing.
        .setUsePlatformDiagnostics(false)
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

    private fun source(manifest: DownloadManifest, cover: Uri?): ConcatenatingMediaSource2 {
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
            // The timeline is laid out from these durations before each file is read.
            val length = (file.durationSec * MS).roundToLong().coerceAtLeast(1)
            builder.add(files.createMediaSource(MediaItem.fromUri(uri)), length)
        }
        return builder.build()
    }

    private fun publish(manifest: DownloadManifest) {
        val exo = exo ?: return
        _now.value = LocalPlaybackState(
            manifest,
            exo.currentPosition,
            SystemClock.elapsedRealtime(),
            exo.isPlaying,
            cover,
        )
    }

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

    private fun place(): DownloadPosition? {
        if (resuming != null) return null
        val exo = exo ?: return null
        val manifest = _now.value?.manifest ?: return null
        val finished = exo.playbackState == Player.STATE_ENDED
        return DownloadPosition(manifest, LocalProgress(exo.currentPosition / MS, System.currentTimeMillis(), finished))
    }

    private inner class ChapterPlayer(player: ExoPlayer) : ForwardingPlayer(player) {
        private val starts: List<Long>
            get() = _now.value?.manifest?.chapters.orEmpty().map { (it.startSec * MS).roundToLong() }.sorted()

        override fun getAvailableCommands(): Player.Commands {
            val commands = super.getAvailableCommands().buildUpon()
            if (starts.size >= 2) commands.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS)
            return commands.build()
        }

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        // While the place is looked up, a controller's play only marks the item to start there.
        override fun play() {
            if (resuming != null) startWhenResumed = true else super.play()
        }

        override fun pause() {
            startWhenResumed = false
            super.pause()
        }

        override fun seekToNext() {
            ChapterNavigation.nextStart(starts, currentPosition)?.let(::seekTo)
        }

        override fun seekToPrevious() {
            ChapterNavigation.previousStart(
                starts,
                currentPosition,
                RemotePlayer.MA_RESTART_THRESHOLD_MS,
            )?.let(::seekTo)
                ?: seekTo(0)
        }
    }

    private companion object {
        const val MS = 1000.0
        const val REMOTE_WAIT_MS = 3_000L
    }
}
