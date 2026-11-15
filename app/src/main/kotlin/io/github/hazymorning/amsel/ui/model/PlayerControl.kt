package io.github.hazymorning.amsel.ui.model

import androidx.compose.runtime.Immutable
import io.github.hazymorning.amsel.api.RepeatMode

/** What the transport, seek bar and volume ask of the speaker. Play, pause, the modes and the volume show at once. */
@Immutable
sealed interface PlayerControl {
    data object PlayPause : PlayerControl

    data object Previous : PlayerControl

    data object Next : PlayerControl

    /** A book or episode: 15 seconds back. */
    data object SeekBack : PlayerControl

    /** A book or episode: 30 seconds forward. */
    data object SeekForward : PlayerControl

    /** Sent once, when the finger lifts from the seek bar. */
    data class Seek(val positionMs: Long) : PlayerControl

    data class SetShuffle(val on: Boolean) : PlayerControl

    data class SetRepeat(val mode: RepeatMode) : PlayerControl

    /** Sent while dragging, so the speaker follows the finger; 0..100. */
    data class SetVolume(val volume: Int) : PlayerControl
}

/** The repeat button cycles Off, All, One, like every mainstream player. */
fun RepeatMode.next(): RepeatMode = when (this) {
    RepeatMode.Off -> RepeatMode.All
    RepeatMode.All -> RepeatMode.One
    RepeatMode.One -> RepeatMode.Off
}
