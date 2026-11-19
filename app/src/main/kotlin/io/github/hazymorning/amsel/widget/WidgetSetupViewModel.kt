package io.github.hazymorning.amsel.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class WidgetSetupUi(
    val slots: List<WidgetSlot> = DefaultSlots,
    val look: WidgetLook = WidgetLook(),
    val cover: WidgetCover? = null,
    /** The Play … buttons' faces, by their cover's address. */
    val thumbs: Map<String, Bitmap> = emptyMap(),
    /** The volume limit, 0 for none: Louder stops there. */
    val volumeLimit: Int = 0,
)

/** One widget's four buttons while its page is open; saved by Done alone. */
class WidgetSetupViewModel(private val widgetId: Int, private val widgets: Widgets, volumeLimit: Flow<Int>) :
    ViewModel() {
    private val slots = MutableStateFlow<List<WidgetSlot>?>(null)
    private val thumbs = MutableStateFlow<Map<String, Bitmap>>(emptyMap())

    val state: StateFlow<WidgetSetupUi> =
        combine(slots.filterNotNull(), widgets.look, widgets.cover, thumbs, volumeLimit, ::ui)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), WidgetSetupUi())

    init {
        viewModelScope.launch {
            widgets.showStored()
            slots.value = widgets.slots(widgetId)
        }
        viewModelScope.launch { slots.filterNotNull().collect { thumbs.value = widgets.thumbs(it) } }
    }

    fun choose(index: Int, button: WidgetButton) = change(index, WidgetSlot(button))

    fun pick(index: Int, item: WidgetItem) = change(index, WidgetSlot(WidgetButton.PlayItem, item))

    private fun ui(
        chosen: List<WidgetSlot>,
        look: WidgetLook?,
        cover: WidgetCover?,
        faces: Map<String, Bitmap>,
        limit: Int,
    ) = WidgetSetupUi(chosen, look ?: WidgetLook(), cover, faces, limit)

    private fun change(index: Int, slot: WidgetSlot) {
        slots.update { current -> current?.toMutableList()?.apply { set(index, slot) } }
    }

    /** Saves the buttons and draws the widget with them, then [then]. */
    fun done(then: () -> Unit) {
        val chosen = slots.value ?: DefaultSlots
        viewModelScope.launch {
            widgets.setUp(widgetId, chosen)
            then()
        }
    }

    private companion object {
        const val STOP_AFTER_MS = 5_000L
    }
}
