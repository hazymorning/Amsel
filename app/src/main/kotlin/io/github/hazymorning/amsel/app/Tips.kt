package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.data.Prefs
import io.github.hazymorning.amsel.ui.navigation.BarPlace
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
 * What the app keeps out of sight so its screens stay plain, and nobody finds untold. One line
 * each, said once, in this order. A new one is a name here, its sentence and its glyph, and a call
 * of [Tips.seen] where the person does what it tells of.
 */
enum class Tip(val id: String) {
    EditHome("edit_home"),
    Settings("settings"),
    More("more"),
}

/**
 * The tip to show, if any: the first that was not seen, one at a time. It stays until it is put
 * away or the person does what it tells of, and is never shown again; the next waits for another
 * day, so the app does not read as a manual. [inFront] looks again each time the app is opened.
 */
class Tips(
    private val prefs: StateFlow<Prefs?>,
    inFront: StateFlow<Boolean>,
    private val save: suspend (id: String, restDay: Long?) -> Unit,
    private val scope: CoroutineScope,
    private val today: () -> Long = { LocalDate.now().toEpochDay() },
) {
    /** What was seen since the app started, held here until the store has it. */
    private val left = MutableStateFlow(Left())

    val current: StateFlow<Tip?> = combine(prefs, inFront, left) { stored, _, left ->
        val day = today()
        when {
            stored == null || stored.tipRestDay == day || left.restDay == day -> null
            else -> Tip.entries.firstOrNull { it.id !in stored.tipsSeen && it.id !in left.ids && it.applies(stored) }
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** [tip] was put away, or the person did what it tells of, with it or without. */
    fun seen(tip: Tip) {
        if (tip.id in left.value.ids || prefs.value?.tipsSeen?.contains(tip.id) == true) return
        val restDay = today().takeIf { tip == current.value }
        left.update { Left(it.ids + tip.id, restDay ?: it.restDay) }
        scope.launch { save(tip.id, restDay) }
    }

    private data class Left(val ids: Set<String> = emptySet(), val restDay: Long? = null)
}

/** Settings' tip tells of the pull down, which opens them only while they have no place in the bar. */
private fun Tip.applies(prefs: Prefs): Boolean =
    this != Tip.Settings || BarPlace.Settings !in BarPlace.of(prefs.barPlaces)
