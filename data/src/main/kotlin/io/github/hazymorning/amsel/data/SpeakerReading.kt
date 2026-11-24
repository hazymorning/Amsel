package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.PlayerQueue

// A speaker whose power control says off is shown idle: the queue can stay "playing" while the speaker's own
// input plays. See docs/API-NOTES.md, "Player fields used".
internal fun Player.asShown(): Player =
    if (isOff) copy(playbackState = IDLE, activeSource = null, currentMedia = null) else this

private const val IDLE = "idle"

internal val PlayerQueue?.speed: Double get() = this?.playbackSpeed?.takeIf { it > 0 } ?: 1.0

internal val PlayerQueue.currentUri: String? get() = currentItem?.mediaItem?.uri

// True for a player not known yet: it has no other source.
fun PlayerQueue.isSourceOf(player: Player?): Boolean =
    player == null || (!player.isOff && (active || player.activeSource == queueId))
