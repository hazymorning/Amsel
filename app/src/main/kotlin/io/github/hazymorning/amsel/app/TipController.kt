package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.data.Prefs
import io.github.hazymorning.amsel.ui.navigation.BarItem
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Shown once each, in declaration order. A new tip needs its sentence and glyph, and a call of
 * [TipController.seen] where the user does what it tells of.
 */
enum class Tip(val id: String) {
    EditHome("edit_home"),
    Settings("settings"),
    More("more"),
}

// After a tip is seen the next waits for another day, so the app does not read as a manual.
class TipController(
    private val prefs: StateFlow<Prefs?>,
    inFront: StateFlow<Boolean>,
    private val save: suspend (id: String, restDay: Long?) -> Unit,
    private val scope: CoroutineScope,
    private val today: () -> Long = { LocalDate.now().toEpochDay() },
) {
    // Seen in this run, held until the store has it.
    private val left = MutableStateFlow(Left())

    // inFront only triggers a new look, with today's date, each time the app is opened.
    val current: StateFlow<Tip?> = combine(prefs, inFront, left) { stored, _, left ->
        val day = today()
        when {
            stored == null || stored.tipRestDay == day || left.restDay == day -> null
            else -> Tip.entries.firstOrNull { it.id !in stored.tipsSeen && it.id !in left.ids && it.applies(stored) }
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Call when the tip is put away, or when the user does what it tells of, shown or not. */
    fun seen(tip: Tip) {
        if (tip.id in left.value.ids || prefs.value?.tipsSeen?.contains(tip.id) == true) return
        val restDay = today().takeIf { tip == current.value }
        left.update { Left(it.ids + tip.id, restDay ?: it.restDay) }
        scope.launch { save(tip.id, restDay) }
    }

    private data class Left(val ids: Set<String> = emptySet(), val restDay: Long? = null)
}

// The Settings tip tells of the pull-down, which opens Settings only while they have no place in the bar.
private fun Tip.applies(prefs: Prefs): Boolean =
    this != Tip.Settings || BarItem.Settings !in BarItem.of(prefs.barPlaces)
