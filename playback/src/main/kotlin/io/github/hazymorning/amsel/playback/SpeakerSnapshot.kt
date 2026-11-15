package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.api.Images
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.PlayerMedia
import io.github.hazymorning.amsel.api.QueueItem
import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.data.PositionAnchor
import io.github.hazymorning.amsel.data.QueueState
import io.github.hazymorning.amsel.data.isSourceOf
import kotlin.math.roundToLong

/**
 * One Music Assistant speaker as [snapshotOf] reads it from its player and queue, a new instance per
 * server event. [elapsedSeconds] is the position at [elapsedAtMs] on the phone's monotonic clock.
 */
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
    /**
     * Shuffle and repeat can be changed: they order a queue of songs. Not a mix the server keeps
     * flowing, which has them fixed, nor a station, which is one stream, nor what another source
     * plays on the speaker.
     */
    val canShuffle: Boolean = true,
    val speed: Double = 1.0,
) {
    /** The position moves on with time. */
    val runs: Boolean get() = playing && !seeking

    /** Where the speaker is at [nowMs] on the phone's monotonic clock. */
    fun positionAt(nowMs: Long): Long = positionAt(
        (elapsedSeconds * 1000).toLong(),
        elapsedAtMs,
        nowMs,
        runs,
        speed,
        (current?.durationSeconds ?: 0) * 1000L,
    )

    /** As last heard, at rest where it was at [nowMs]: not playing, nothing starting. */
    fun standingAt(nowMs: Long): SpeakerSnapshot = copy(
        playing = false,
        playActionInProgress = false,
        seeking = false,
        elapsedSeconds = positionAt(nowMs) / 1000.0,
        elapsedAtMs = nowMs,
    )
}

/**
 * A position reported at [atMs], moved on to [nowMs] at [speed] while [playing]; within [durationMs]
 * when that is known.
 */
fun positionAt(positionMs: Long, atMs: Long, nowMs: Long, playing: Boolean, speed: Double, durationMs: Long): Long {
    val moved = if (playing) positionMs + ((nowMs - atMs).coerceAtLeast(0) * speed).toLong() else positionMs
    return if (durationMs > 0) moved.coerceIn(0, durationMs) else moved.coerceAtLeast(0)
}

/**
 * A `QueueItem`, or what another source (an external input, AirPlay) reports playing. [queueItemId]
 * is the stable identity (never the track id; for another source its uri or title). [artist] is a
 * book's authors and an episode's podcast.
 */
data class QueueEntry(
    val queueItemId: String,
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationSeconds: Int?,
    /** `/imageproxy/<proxy_id>?size=512` on the connection base. */
    val artworkUrl: String?,
    /** The item's `media_type`: "track", "radio", "audiobook" or "podcast_episode". */
    val mediaType: String = MediaType.Track.api,
    /** A book's or episode's chapter starts, ascending. */
    val chapterStartsMs: List<Long> = emptyList(),
) {
    /** A book or an episode: it skips by seconds, and next and previous step by chapter. */
    val spoken: Boolean get() = MediaType.isSpokenWord(mediaType)

    /**
     * The second a seek to [positionMs] goes to. The server cuts a seek to whole seconds, refuses
     * one to the very end, and takes 0 on a book or an episode as "resume from the bookmark", so a
     * spoken item never seeks below 1 s. [intoSecond] rounds up, so a chapter's start lands inside
     * that chapter, not at the end of the one before.
     */
    fun seekSeconds(positionMs: Long, intoSecond: Boolean = false): Int {
        val floor = if (spoken) 1 else 0
        val ms = positionMs.coerceAtLeast(0) + if (intoSecond) 999 else 0
        val last = durationSeconds?.minus(1)?.takeIf { it >= floor } ?: Int.MAX_VALUE
        return (ms / 1000).toInt().coerceIn(floor, last)
    }
}

/**
 * One speaker as the session and Now Playing see it; an inactive queue's item is not what plays.
 * [state] is its queue and where that is, [mediaPosition] the place of what the speaker reports
 * playing.
 */
fun snapshotOf(
    player: Player,
    state: QueueState?,
    mediaPosition: PositionAnchor?,
    pending: Boolean,
    images: Images?,
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

/** Another source plays (an external input, AirPlay, Spotify Connect): only what it reports. */
private fun entryOf(media: PlayerMedia, images: Images?) = QueueEntry(
    queueItemId = media.queueItemId ?: media.uri ?: media.title.orEmpty(),
    uri = media.uri.orEmpty(),
    title = media.title.orEmpty(),
    artist = media.artist,
    album = media.album,
    durationSeconds = media.duration?.toInt(),
    artworkUrl = images?.rebase(media.imageUrl)?.let { Images.sized(it, 512) },
    mediaType = media.mediaType ?: MediaType.Track.api,
)

private fun entryOf(item: QueueItem, images: Images?): QueueEntry {
    val media = item.mediaItem
    return QueueEntry(
        queueItemId = item.queueItemId,
        uri = media?.uri.orEmpty(),
        title = media?.name ?: item.name,
        artist = media?.subtitle,
        album = media?.album?.name,
        durationSeconds = media?.duration ?: item.duration,
        artworkUrl = images?.url(media?.cover ?: item.image)?.let { Images.sized(it, 512) },
        mediaType = media?.mediaType ?: MediaType.Track.api,
        chapterStartsMs = media?.chapters.orEmpty().map { (it.start * 1000).roundToLong() }.sorted(),
    )
}
