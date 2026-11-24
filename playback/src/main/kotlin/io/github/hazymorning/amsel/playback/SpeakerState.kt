@file:OptIn(UnstableApi::class)

package io.github.hazymorning.amsel.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import io.github.hazymorning.amsel.api.RepeatMode

internal fun speakerState(s: SpeakerSnapshot?, phone: Boolean, elapsedRealtime: () -> Long): SimpleBasePlayer.State {
    val builder =
        SimpleBasePlayer.State
            .Builder()
            .setAvailableCommands(availableCommands(s, phone))
            .setDeviceInfo(deviceInfo(s, phone))
            .setDeviceVolume(s?.volumeLevel?.coerceIn(0, RemotePlayer.MAX_VOLUME) ?: 0)
            .setIsDeviceMuted(s?.volumeMuted ?: false)
            .setMaxSeekToPreviousPositionMs(RemotePlayer.MA_RESTART_THRESHOLD_MS)
            .setSeekBackIncrementMs(SKIP_BACK_MS)
            .setSeekForwardIncrementMs(SKIP_FORWARD_MS)
    val current = s?.current
    if (s == null || current == null) {
        return builder
            .setPlayWhenReady(s?.playActionInProgress == true, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(Player.STATE_IDLE)
            .build()
    }
    val playlist =
        buildList {
            add(itemData(current, s, phone))
            // With repeat "one" MA can report the current item as next: duplicate uids throw.
            s.next?.takeIf { it.queueItemId != current.queueItemId }?.let { add(itemData(it, s, phone)) }
        }
    // A play starting or a seek on its way buffers: the speaker will play but does not yet.
    val starting = s.playActionInProgress || s.seeking
    return builder
        .setPlaylist(playlist)
        .setCurrentMediaItemIndex(0)
        .setPlayWhenReady(
            s.playing || starting,
            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            // A stopped speaker keeps its item, so READY: the car reads IDLE as nothing to play and
            // closes its playback screen.
        ).setPlaybackState(if (starting) Player.STATE_BUFFERING else Player.STATE_READY)
        .setContentPositionMs(positionSupplier(s, elapsedRealtime))
        .setPlaybackParameters(PlaybackParameters(s.speed.toFloat()))
        .setShuffleModeEnabled(s.shuffle)
        .setRepeatMode(
            when (s.repeat) {
                RepeatMode.Off -> Player.REPEAT_MODE_OFF
                RepeatMode.One -> Player.REPEAT_MODE_ONE
                RepeatMode.All -> Player.REPEAT_MODE_ALL
            },
        ).build()
}

// The deprecated non-flag volume commands stay on for controllers built on older Media3.
@Suppress("DEPRECATION")
private fun availableCommands(s: SpeakerSnapshot?, phone: Boolean): Player.Commands {
    val b =
        Player.Commands.Builder().addAll(
            Player.COMMAND_RELEASE,
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_MEDIA_ITEM,
            // Transport stays available while idle or reconnecting: an empty action mask makes
            // voice hosts and some car head units ignore the session. Stop is one they require.
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
        )
    if (s == null || !s.available) return b.build()
    b.addAll(
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_TIMELINE,
        Player.COMMAND_GET_METADATA,
        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
    )
    if (s.canShuffle) b.addAll(Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE)
    if (s.canSkip) {
        b.addAll(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
    }
    if (s.canSeek) b.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    if (s.canSeek && s.current?.spoken == true) b.addAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
    // This phone's volume is the phone's own media volume (the module ignores server volume).
    if (s.canSetVolume && !phone) {
        b.addAll(
            Player.COMMAND_SET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        )
    }
    return b.build()
}

/**
 * Media3 rebuilds the platform VolumeProvider only when DeviceInfo changes (or the player is
 * swapped), not when volume commands change, so maxVolume carries "controllable" (100) vs
 * fixed (0) to force a rebuild when a speaker's volume_control changes.
 */
private fun deviceInfo(s: SpeakerSnapshot?, phone: Boolean): DeviceInfo = if (phone) {
    DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL).build()
} else {
    DeviceInfo
        .Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
        .setMinVolume(0)
        .setMaxVolume(if (s?.canSetVolume == true) RemotePlayer.MAX_VOLUME else 0)
        .build()
}

private fun itemData(e: QueueEntry, s: SpeakerSnapshot, phone: Boolean): SimpleBasePlayer.MediaItemData {
    val metadata =
        MediaMetadata
            .Builder()
            .setTitle(e.title)
            // The system output chip reads "Other device" for a remote session without a
            // MediaRouter2 route, so the artist line names the speaker; not for this phone.
            .setArtist(if (phone) e.artist else listOfNotNull(e.artist, s.name).joinToString(" · "))
            .setAlbumTitle(e.album)
            .setArtworkUri(e.artworkUrl?.let(Uri::parse))
            .setDurationMs(e.durationSeconds?.let { it * 1000L })
            .setMediaType(media3Type(e.mediaType, browsable = false))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
    return SimpleBasePlayer.MediaItemData
        .Builder(e.queueItemId)
        .setMediaItem(
            MediaItem
                .Builder()
                .setMediaId(e.queueItemId)
                .setMediaMetadata(metadata)
                .build(),
        ).setDurationUs(e.durationSeconds?.let { it * 1_000_000L } ?: C.TIME_UNSET)
        .setIsSeekable(s.canSeek)
        .build()
}

private fun positionSupplier(s: SpeakerSnapshot, elapsedRealtime: () -> Long): SimpleBasePlayer.PositionSupplier =
    if (s.runs) {
        SimpleBasePlayer.PositionSupplier { s.positionAt(elapsedRealtime()) }
    } else {
        SimpleBasePlayer.PositionSupplier.getConstant(s.positionAt(elapsedRealtime()))
    }
