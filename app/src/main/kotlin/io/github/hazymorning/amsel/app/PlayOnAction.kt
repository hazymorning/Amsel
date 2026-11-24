package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.ui.model.SpeakerUi

// The server is sensitive to the order of these steps, so moveMusic keeps it in one tested place.
internal interface SpeakerSteps {
    suspend fun select(target: SpeakerUi)

    /** Runs [block] with This phone kept on the server, even if another speaker is selected meanwhile. */
    suspend fun keepPhoneDuring(block: suspend () -> Unit)

    /** Throws [PhoneNotReadyException] if This phone's Sendspin client does not join the server. */
    suspend fun awaitPhone(): String

    /** Ends the local pause that keeps This phone silent. */
    fun releasePhoneHold()

    /** `player_queues/transfer` with `auto_play` = [play]. */
    suspend fun transfer(from: String, to: String, play: Boolean)
}

// Away from This phone, the phone stays on the server until the transfer is done: the server drops
// a disconnected phone's queue, and selecting another speaker disconnects it.
// See docs/API-NOTES.md, "This phone (Sendspin)".
internal suspend fun moveMusic(from: SpeakerUi, to: SpeakerUi, steps: SpeakerSteps, play: Boolean = true) {
    val move: suspend () -> Unit = {
        steps.select(to)
        val target = if (to.isPhone) steps.awaitPhone().also { if (play) steps.releasePhoneHold() } else to.id
        if (target != from.id) steps.transfer(from.id, target, play)
    }
    if (from.isPhone) steps.keepPhoneDuring(move) else move()
}

enum class PlayOnAction {
    Stay,

    /** The music stays where it is. */
    Select,

    Move,

    /** Music plays that would stop or be replaced: the user decides. */
    Ask,
}

// This phone's music is private and moves along, playing or paused, unless the tapped speaker plays.
// A speaker is shared: its playing music is stopped or replaced only when the user says so, and
// paused music stays on it.
internal fun choiceOf(
    sameSpeaker: Boolean,
    fromPhone: Boolean,
    holds: Boolean,
    plays: Boolean,
    targetPlays: Boolean,
): PlayOnAction = when {
    sameSpeaker -> PlayOnAction.Stay
    !holds -> PlayOnAction.Select
    fromPhone && !targetPlays -> PlayOnAction.Move
    plays -> PlayOnAction.Ask
    else -> PlayOnAction.Select
}
