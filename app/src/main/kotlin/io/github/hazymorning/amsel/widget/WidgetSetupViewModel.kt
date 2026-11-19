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

/** A speaker a widget can keep to. */
@Immutable
data class WidgetSpeaker(val id: String, val name: String)

@Immutable
data class WidgetSetupUi(
    val setup: WidgetSetup = WidgetSetup(),
    val look: WidgetLook = WidgetLook(),
    /** The widget as it will show, at its size; null until drawn. */
    val picture: RemoteViews? = null,
    /** Its size on the home screen, in dp. */
    val size: SizeF = WidgetKind.NowPlaying.start,
    /** How many of its buttons show at that size. */
    val fits: Int = 0,
    val speakers: List<WidgetSpeaker> = emptyList(),
    /** The volume limit, 0 for none: Louder stops there. */
    val volumeLimit: Int = 0,
)

/** One widget's look, speaker and buttons while its page is open; saved by Done alone. */
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetSetupViewModel(
    private val widgetId: Int,
    private val widgets: Widgets,
    speakers: Flow<List<WidgetSpeaker>>,
    volumeLimit: Flow<Int>,
) : ViewModel() {
    private val setup = MutableStateFlow<WidgetSetup?>(null)
    private val size = widgets.size(widgetId)

    private val look: Flow<WidgetLook> = setup.filterNotNull()
        .map { it.speaker }
        .distinctUntilChanged()
        .flatMapLatest { widgets.lookFor(it) }
        .onStart { emit(WidgetLook()) }

    private val picture: Flow<RemoteViews?> = combine(setup.filterNotNull(), look, ::Pair)
        .mapLatest<Pair<WidgetSetup, WidgetLook>, RemoteViews?> { (setup, look) ->
            widgets.picture(widgetId, setup, look, size)
        }
        .onStart { emit(null) }

    val state: StateFlow<WidgetSetupUi> =
        combine(setup.filterNotNull(), look, picture, speakers, volumeLimit) { setup, look, picture, rooms, limit ->
            val fits = widgets.views.fit(size.width, size.height, setup).buttons
            WidgetSetupUi(setup, look, picture, size, fits, rooms, limit)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), WidgetSetupUi(size = size))

    init {
        viewModelScope.launch { setup.value = widgets.setup(widgetId) }
    }

    fun style(style: WidgetStyle) = change { it.copy(style = style) }

    /** Null: where Amsel plays. */
    fun speaker(id: String?) = change { it.copy(speaker = id) }

    /** Button [index] does [button]; one past the last adds it. */
    fun choose(index: Int, button: WidgetButton) = put(index, WidgetSlot(button))

    fun pick(index: Int, item: WidgetItem) = put(index, WidgetSlot(WidgetButton.PlayItem, item))

    fun remove(index: Int) = change { it.copy(slots = it.slots.filterIndexed { i, _ -> i != index }) }

    /** The buttons in a new [order], given as their places in the old one. */
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

    /** Saves the setup and draws the widget with it, then [then]. */
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
