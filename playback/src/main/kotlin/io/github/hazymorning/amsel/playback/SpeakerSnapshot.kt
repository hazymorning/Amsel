package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.api.ImageUrls
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.PlayerMedia
import io.github.hazymorning.amsel.api.QueueItem
import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.data.PositionAnchor
import io.github.hazymorning.amsel.data.QueueState
import io.github.hazymorning.amsel.data.isSourceOf
import kotlin.math.roundToLong

/** [elapsedSeconds] is the position at [elapsedAtMs] on the phone's monotonic clock. */
data class SpeakerSnapshot(
    val name: String,
    val available: Boolean,
    val playing: Boolean,
    val playActionInProgress: Boolean = false,
    val current: QueueEntry? = null,
    val next: QueueEntry? = null,
    val elapsedSeconds: Double = 0.0,
    val elapsedAtMs: Long = 0L,
    /** A seek is on its way: the position is its target, standing still until the speaker plays there. */
    val seeking: Boolean = false,
    val volumeLevel: Int? = null,
    val volumeMuted: Boolean = false,
    val canSetVolume: Boolean = false,
    val canSeek: Boolean = false,
    val canSkip: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    /** False for a dynamic queue (the server fixes its shuffle and repeat), a station or another source. */
    val canShuffle: Boolean = true,
    val speed: Double = 1.0,
) {
    val runs: Boolean get() = playing && !seeking

    fun positionAt(nowMs: Long): Long = positionAt(
        (elapsedSeconds * 1000).toLong(),
        elapsedAtMs,
        nowMs,
        runs,
        speed,
        (current?.durationSeconds ?: 0) * 1000L,
    )

    fun standingAt(nowMs: Long): SpeakerSnapshot = copy(
        playing = false,
        playActionInProgress = false,
        seeking = false,
        elapsedSeconds = positionAt(nowMs) / 1000.0,
        elapsedAtMs = nowMs,
    )
}

fun positionAt(positionMs: Long, atMs: Long, nowMs: Long, playing: Boolean, speed: Double, durationMs: Long): Long {
    val moved = if (playing) positionMs + ((nowMs - atMs).coerceAtLeast(0) * speed).toLong() else positionMs
    return if (durationMs > 0) moved.coerceIn(0, durationMs) else moved.coerceAtLeast(0)
}

/** [queueItemId] is the identity, never the track id; for another source its uri or title. */
data class QueueEntry(
    val queueItemId: String,
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationSeconds: Int?,
    val artworkUrl: String?,
    val mediaType: String = MediaType.Track.api,
    /** Ascending, as ChapterNavigation needs them. */
    val chapterStartsMs: List<Long> = emptyList(),
) {
    val spoken: Boolean get() = MediaType.isSpokenWord(mediaType)

    /**
     * The server cuts a seek to whole seconds, refuses one to the very end and takes 0 on a spoken item
     * as "resume" (docs/API-NOTES.md, "Seek"). [intoSecond] rounds up into a chapter that starts
     * mid-second.
     */
    fun seekSeconds(positionMs: Long, intoSecond: Boolean = false): Int {
        val floor = if (spoken) 1 else 0
        val ms = positionMs.coerceAtLeast(0) + if (intoSecond) 999 else 0
        val last = durationSeconds?.minus(1)?.takeIf { it >= floor } ?: Int.MAX_VALUE
        return (ms / 1000).toInt().coerceIn(floor, last)
    }
}

/** An inactive queue's item is not what plays: then the player's current media and [mediaPosition] count. */
fun snapshotOf(
    player: Player,
    state: QueueState?,
    mediaPosition: PositionAnchor?,
    pending: Boolean,
    images: ImageUrls?,
    nowMs: Long,
): SpeakerSnapshot {
    val queue = state?.queue
    // A speaker switched off plays nothing, though its queue may still say it does.
    val queueActive = queue != null && queue.isSourceOf(player)
    val current = if (queueActive) {
        queue.currentItem?.let { entryOf(it, images) }
    } else {
        player.currentMedia?.let { entryOf(it, images) }
    }
    val at = if (queueActive) state.position else mediaPosition
    return SpeakerSnapshot(
        name = player.shownName,
        available = player.available,
        playing = if (queueActive) state.isPlaying else player.isPlaying,
        playActionInProgress = pending || queue?.playActionInProgress == true,
        current = current,
        next = queue?.nextItem?.takeIf { queueActive }?.let { entryOf(it, images) },
        elapsedSeconds = at?.seconds ?: 0.0,
        elapsedAtMs = at?.atElapsedMs ?: nowMs,
        seeking = queueActive && state.seeking,
        volumeLevel = player.volume,
        volumeMuted = player.muted,
        canSetVolume = player.hasVolume,
        // The Music Assistant queue handles seek and skip for its own speakers.
        canSeek = (queueActive || "seek" in player.supportedFeatures) && (current?.durationSeconds ?: 0) > 0,
        canSkip = queueActive || "next_previous" in player.supportedFeatures,
        shuffle = queue?.shuffleEnabled == true,
        repeat = queue?.repeatMode ?: RepeatMode.Off,
        canShuffle = queueActive && !queue.isDynamic && current?.mediaType != MediaType.Radio.api,
        speed = queue?.playbackSpeed?.takeIf { queueActive && it > 0 } ?: 1.0,
    )
}

private fun entryOf(media: PlayerMedia, images: ImageUrls?) = QueueEntry(
    queueItemId = media.queueItemId ?: media.uri ?: media.title.orEmpty(),
    uri = media.uri.orEmpty(),
    title = media.title.orEmpty(),
    artist = media.artist,
    album = media.album,
    durationSeconds = media.duration?.toInt(),
    artworkUrl = images?.rebase(media.imageUrl)?.let { ImageUrls.sized(it, 512) },
    mediaType = media.mediaType ?: MediaType.Track.api,
)

private fun entryOf(item: QueueItem, images: ImageUrls?): QueueEntry {
    val media = item.mediaItem
    return QueueEntry(
        queueItemId = item.queueItemId,
        uri = media?.uri.orEmpty(),
        title = media?.name ?: item.name,
        artist = media?.subtitle,
        album = media?.album?.name,
        durationSeconds = media?.duration ?: item.duration,
        artworkUrl = images?.url(media?.cover ?: item.image)?.let { ImageUrls.sized(it, 512) },
        mediaType = media?.mediaType ?: MediaType.Track.api,
        chapterStartsMs = media?.chapters.orEmpty().map { (it.start * 1000).roundToLong() }.sorted(),
    )
}
