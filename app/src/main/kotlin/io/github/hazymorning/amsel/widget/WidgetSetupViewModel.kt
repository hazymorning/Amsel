package io.github.hazymorning.amsel.widget

import android.util.SizeF
import android.widget.RemoteViews
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class WidgetSpeaker(val id: String, val name: String)

@Immutable
data class WidgetSetupUiState(
    val setup: WidgetSetup = WidgetSetup(),
    val look: WidgetContent = WidgetContent(),
    val picture: RemoteViews? = null,
    /** In dp. */
    val size: SizeF = WidgetKind.NowPlaying.start,
    /** How many buttons fit at [size]. */
    val fits: Int = 0,
    /** Indices into the setup's slots of the buttons that show ([standing]). */
    val standing: Set<Int> = emptySet(),
    val speakers: List<WidgetSpeaker> = emptyList(),
    /** 0 for none. */
    val volumeLimit: Int = 0,
)

/** Changes stay in memory until [done] saves them. */
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSetupViewModel(
    private val widgetId: Int,
    private val widgets: WidgetController,
    speakers: Flow<List<WidgetSpeaker>>,
    volumeLimit: Flow<Int>,
) : ViewModel() {
    private val setup = MutableStateFlow<WidgetSetup?>(null)
    private val size = widgets.size(widgetId)

    private val look: Flow<WidgetContent> = setup.filterNotNull()
        .map { it.speaker }
        .distinctUntilChanged()
        .flatMapLatest { widgets.contentFor(it) }
        .onStart { emit(WidgetContent()) }

    private val picture: Flow<RemoteViews?> = combine(setup.filterNotNull(), look, ::Pair)
        .mapLatest<Pair<WidgetSetup, WidgetContent>, RemoteViews?> { (setup, look) ->
            widgets.picture(widgetId, setup, look, size)
        }
        .onStart { emit(null) }

    val state: StateFlow<WidgetSetupUiState> =
        combine(setup.filterNotNull(), look, picture, speakers, volumeLimit) { setup, look, picture, rooms, limit ->
            val fits = widgets.views.fit(size.width, size.height, setup).buttons
            val standing = standing(setup.slots.indices.toList(), fits) { setup.slots[it].button }.toSet()
            WidgetSetupUiState(setup, look, picture, size, fits, standing, rooms, limit)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), WidgetSetupUiState(size = size))

    init {
        viewModelScope.launch { setup.value = widgets.setup(widgetId) }
    }

    fun style(style: WidgetStyle) = change { it.copy(style = style) }

    /** Null follows the speaker Amsel plays on. */
    fun speaker(id: String?) = change { it.copy(speaker = id) }

    fun cells(cells: WidgetCells) = change { it.copy(cells = cells) }

    fun showWords(on: Boolean) = change { if (!on && it.slots.isEmpty()) it else it.copy(showsWords = on) }

    fun moveWords(place: WidgetWordsPosition) = change { it.copy(words = place) }

    /** [index] one past the last adds a button, up to [MAX_BUTTONS]. */
    fun choose(index: Int, button: WidgetButton) = put(index, WidgetSlot(button))

    fun pick(index: Int, item: WidgetItem) = put(index, WidgetSlot(WidgetButton.PlayItem, item))

    fun remove(index: Int) = change { setup ->
        if (!setup.showsWords && setup.slots.size == 1) {
            setup
        } else {
            setup.copy(slots = setup.slots.filterIndexed { i, _ -> i != index })
        }
    }

    /** [order]: the old indices in their new order; anything but a permutation is ignored. */
    fun reorder(order: List<Int>) = change { setup ->
        if (order.sorted() != setup.slots.indices.toList()) setup else setup.copy(slots = order.map(setup.slots::get))
    }

    private fun put(index: Int, slot: WidgetSlot) = change { setup ->
        val slots = setup.slots.toMutableList()
        if (index < slots.size) {
            slots[index] = slot
        } else if (slots.size < MAX_BUTTONS) {
            slots += slot
        }
        setup.copy(slots = slots)
    }

    private fun change(block: (WidgetSetup) -> WidgetSetup) = setup.update { it?.let(block) }

    fun done(then: () -> Unit) {
        val chosen = setup.value ?: return then()
        viewModelScope.launch {
            widgets.setUp(widgetId, chosen)
            then()
        }
    }

    private companion object {
        const val STOP_AFTER_MS = 5_000L
    }
}
