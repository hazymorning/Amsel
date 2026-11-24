package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.data.DataGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

class SpeakerSelection(data: DataGraph, phone: PhoneSpeaker, scope: CoroutineScope) {
    val target: StateFlow<PlaybackTarget> =
        combine(data.prefs.prefs.filterNotNull().map { it.speakerId }, phone.playerId) { selected, phoneId ->
            if (selected == null ||
                selected == phoneId
            ) {
                PlaybackTarget(phoneId, isPhone = true)
            } else {
                PlaybackTarget(selected, isPhone = false)
            }
        }.onEach { phone.setWanted(it.isPhone) }
            .stateIn(scope, SharingStarted.Eagerly, PlaybackTarget(null, isPhone = true))
}
