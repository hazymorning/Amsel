package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.PlayerQueue

/**
 * A speaker switched off (its power control, such as a Home Assistant entity, says so) plays
 * nothing, whatever its queue still reports: Music Assistant leaves a queue stuck "playing" when the
 * speaker's own input takes over.
 */
internal fun Player.asShown(): Player =
    if (isOff) copy(playbackState = IDLE, activeSource = null, currentMedia = null) else this

private const val IDLE = "idle"

/** How fast the queue's position moves; 1 for an unknown queue or a speed the server left out. */
internal val PlayerQueue?.speed: Double get() = this?.playbackSpeed?.takeIf { it > 0 } ?: 1.0

internal val PlayerQueue.currentUri: String? get() = currentItem?.mediaItem?.uri

/**
 * Whether the queue is what [player] plays, or would play: not while another source has the speaker
 * (AirPlay, one of its inputs), nor while the speaker is switched off. A player not known yet has
 * nothing else to play.
 */
fun PlayerQueue.isSourceOf(player: Player?): Boolean =
    player == null || (!player.isOff && (active || player.activeSource == queueId))
