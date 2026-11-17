package io.github.hazymorning.amsel.ui.screens.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.data.home.HomeItem
import io.github.hazymorning.amsel.data.home.HomeSource
import io.github.hazymorning.amsel.data.home.SectionItems
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.data.home.SectionSource
import io.github.hazymorning.amsel.playback.failureText
import io.github.hazymorning.amsel.ui.model.MediaUi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "See all": everything [option] has, asked when the page opens and again whenever Home loads or
 * changes that option (a pin taken back, a book finished, a refresh); Retry asks again.
 */
class SeeAllViewModel(
    private val option: SectionOption,
    home: HomeSource,
    private val load: suspend (SectionOption) -> SectionItems,
    private val media: (HomeItem) -> MediaUi,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    // Titled from Home's copy until the page's own comes: Recently played may list the library's albums.
    private val _state =
        MutableStateFlow(
            SeeAllUiState(
                option,
                loading = true,
                fallback =
                    home.items.value?.get(option)?.fallback == true,
            ),
        )
    val state: StateFlow<SeeAllUiState> = _state.asStateFlow()
    private var asking: Job? = null

    init {
        reload()
        viewModelScope.launch { home.changes.filter { it == option }.collect { ask() } }
    }

    fun retry() = reload()

    /** On open and from Retry, which shows in place of the items: the skeleton until they come. */
    private fun reload() {
        _state.update { it.copy(loading = true, failure = null) }
        ask()
    }

    /** The latest ask wins; what the page lists stays until its answer. */
    private fun ask() {
        asking?.cancel()
        asking = viewModelScope.launch {
            val loaded = try {
                load(option)
            } catch (e: MaException) {
                return@launch failed(e)
            }
            // A whole library maps off the main thread.
            val items = withContext(worker) { loaded.items.map(media) }
            _state.value = SeeAllUiState(option, items, fallback = loaded.fallback)
        }
    }

    /** Said in place of the items when there are none; a list already shown stays as it is. */
    private fun failed(error: Exception) = _state.update {
        if (it.items.isEmpty()) it.copy(loading = false, failure = failureText(error)) else it.copy(loading = false)
    }
}

/** "See all" for one Home section: everything its option has, an album section as a grid, the rest as rows. */
@Immutable
data class SeeAllUiState(
    val option: SectionOption,
    val items: List<MediaUi> = emptyList(),
    /** Nothing has come yet: draw the skeleton. */
    val loading: Boolean = false,
    /** Why nothing could be loaded, said in place of the items. */
    @StringRes val failure: Int? = null,
    /** Recently played found nothing and lists the library's albums ("Your albums"). */
    val fallback: Boolean = false,
) {
    val grid: Boolean get() = option.source == SectionSource.Albums
}
