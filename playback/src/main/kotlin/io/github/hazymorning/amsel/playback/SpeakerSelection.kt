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

/** The speaker the person picked, or This phone: where the app plays and what it controls. */
class SpeakerSelection(data: DataGraph, phone: PhoneSpeaker, scope: CoroutineScope) {
    /** Prefs hold the picked speaker; null means "This phone", the default. */
    val target: StateFlow<Target> =
        combine(data.prefs.prefs.filterNotNull().map { it.speakerId }, phone.playerId) { selected, phoneId ->
            if (selected == null ||
                selected == phoneId
            ) {
                Target(phoneId, isPhone = true)
            } else {
                Target(selected, isPhone = false)
            }
        }.onEach { phone.setWanted(it.isPhone) }
            .stateIn(scope, SharingStarted.Eagerly, Target(null, isPhone = true))
}
