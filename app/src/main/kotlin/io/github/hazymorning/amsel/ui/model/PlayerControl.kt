package io.github.hazymorning.amsel.ui.model

import androidx.compose.runtime.Immutable
import io.github.hazymorning.amsel.api.RepeatMode

@Immutable
sealed interface PlayerControl {
    data object PlayPause : PlayerControl

    data object Previous : PlayerControl

    data object Next : PlayerControl

    data object SeekBack : PlayerControl

    data object SeekForward : PlayerControl

    /** Sent once, when the finger lifts from the seek bar. */
    data class Seek(val positionMs: Long) : PlayerControl

    data class SetShuffle(val on: Boolean) : PlayerControl

    data class SetRepeat(val mode: RepeatMode) : PlayerControl

    /** 0..100, sent while dragging so the speaker follows the finger. */
    data class SetVolume(val volume: Int) : PlayerControl
}

fun RepeatMode.next(): RepeatMode = when (this) {
    RepeatMode.Off -> RepeatMode.All
    RepeatMode.All -> RepeatMode.One
    RepeatMode.One -> RepeatMode.Off
}
