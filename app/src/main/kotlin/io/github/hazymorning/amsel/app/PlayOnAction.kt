package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.ui.model.SpeakerUi

/**
 * What moving music needs from the speakers, so the order of the steps (which the server is
 * sensitive to) lives in one tested place. [PlayOnController] implements it on the real graph.
 */
internal interface SpeakerSteps {
    suspend fun select(target: SpeakerUi)

    /** Runs [block] with This phone kept on the server, even if another speaker is selected meanwhile. */
    suspend fun keepPhoneDuring(block: suspend () -> Unit)

    /**
     * This phone's player id once its Sendspin client has joined the server; [PhoneNotReadyException]
     * if it does not.
     */
    suspend fun awaitPhone(): String

    /** Lets This phone make sound again after a local pause. */
    fun releasePhoneHold()

    /** `player_queues/transfer`: the music plays on [to] when [play], else it rests there. */
    suspend fun transfer(from: String, to: String, play: Boolean)
}

/**
 * Moves the music from [from] to [to], where it plays on when [play]. Throws what kept it from moving.
 * - Away from This phone, the phone stays on the server until the transfer is done: the server
 *   reads the phone's queue for the transfer and removes it once the phone disconnects, which
 *   selecting another speaker starts.
 * - To This phone, the transfer waits until the phone has joined the server, and releases its
 *   local hold first when it is to play.
 */
internal suspend fun moveMusic(from: SpeakerUi, to: SpeakerUi, steps: SpeakerSteps, play: Boolean = true) {
    val move: suspend () -> Unit = {
        steps.select(to)
        val target = if (to.isPhone) steps.awaitPhone().also { if (play) steps.releasePhoneHold() } else to.id
        if (target != from.id) steps.transfer(from.id, target, play)
    }
    if (from.isPhone) steps.keepPhoneDuring(move) else move()
}

/** What a tap on a speaker in "Play on" does. */
enum class PlayOnAction {
    /** It is the speaker already. */
    Stay,

    /** It becomes the speaker; music stays where it is. */
    Select,

    /** The music goes there with the person. */
    Move,

    /** Music plays that would stop or be replaced: the person says which. */
    Ask,
}

/**
 * This phone's music is its owner's alone and goes along, playing or paused, unless the speaker
 * tapped plays something itself. A speaker is shared: music that plays on one stays, or is
 * replaced, only when the person says so, and what rests on one stays there.
 */
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
