package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.app.MessageController
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.data.home.HomeItem
import io.github.hazymorning.amsel.data.home.HomeLayout
import io.github.hazymorning.amsel.data.home.HomeSource
import io.github.hazymorning.amsel.data.home.SectionItems
import io.github.hazymorning.amsel.data.home.SectionLayout
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.data.home.indexKind
import io.github.hazymorning.amsel.data.library.INDEXED_TYPES
import io.github.hazymorning.amsel.playback.failureText
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.SpokenWord
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Lets the screen be drawn without a ViewModel. */
interface HomeEditor {
    fun startEditing() {}

    fun doneEditing() {}

    fun setOption(id: String, option: SectionOption) {}

    fun setLayout(id: String, layout: SectionLayout) {}

    fun setTitle(id: String, title: String?) {}

    fun setTitled(id: String, titled: Boolean) {}

    /** Moves section [id] to [index] among all sections. */
    fun move(id: String, index: Int) {}

    fun moveUp(id: String) {}

    fun moveDown(id: String) {}

    fun remove(id: String) {}

    fun add() {}

    /** The default sections again; the pins are kept. */
    fun reset() {}

    /** [order]: pin URIs in their new order. */
    fun arrangePins(order: List<String>) {}

    object None : HomeEditor
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val handle: SavedStateHandle,
    private val home: HomeSource,
    session: StateFlow<SessionState>,
    /** While syncing, an index-backed section still empty on its kind's first sync is loading. */
    indexSyncing: Flow<Boolean>,
    /** The kinds the index has synced at least once (LibraryRepository.synced). */
    indexSynced: Flow<Set<MediaType>>,
    libraryEmpty: Flow<Boolean>,
    private val mapper: UiMapper,
    private val messages: MessageController,
    spokenWord: Flow<SpokenWord> = flowOf(SpokenWord()),
    speakers: Flow<HomeSpeakers> = flowOf(HomeSpeakers()),
    editOnly: Boolean = false,
) : ViewModel(),
    HomeEditor {
    // Saved, so an edit sheet restored after process death opens in edit mode again.
    private val editing = handle.getStateFlow(EDITING, editOnly)

    private val inputs = combine(
        home.layout,
        home.items,
        session.map(::isOffline).distinctUntilChanged(),
        combine(indexSyncing, indexSynced) { syncing, synced ->
            if (syncing) INDEXED_TYPES.toSet() - synced else emptySet()
        }.distinctUntilChanged(),
        speakers.distinctUntilChanged(),
    ) { layout, items, offline, firstSyncs, speakers -> HomeInputs(layout, items, offline, firstSyncs, speakers) }

    val state: StateFlow<HomeUiState> =
        combine(inputs, editing, spokenWord.distinctUntilChanged()) { inputs, editing, held ->
            homeState(inputs, editing, mapper::media).copy(spoken = held)
        }.flatMapLatest { state ->
            // Only a Home with nothing to show asks the index, so no other state waits for it.
            if (state.showsNothing) libraryEmpty.map { state.copy(libraryEmpty = it) } else flowOf(state)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            // Computed from the cache, so launch shows no skeleton flash.
            homeState(
                HomeInputs(
                    home.layout.value,
                    home.items.value,
                    isOffline(session.value),
                    firstSyncs = emptySet(),
                ),
                editing = editing.value,
                mapper::media,
            ),
        )

    override fun startEditing() {
        handle[EDITING] = true
    }

    override fun doneEditing() {
        handle[EDITING] = false
    }

    override fun setOption(id: String, option: SectionOption) = edit { it.setOption(id, option) }

    override fun setLayout(id: String, layout: SectionLayout) = edit { it.setLayout(id, layout) }

    override fun setTitle(id: String, title: String?) = edit { it.setTitle(id, title) }

    override fun setTitled(id: String, titled: Boolean) = edit { it.setTitled(id, titled) }

    override fun move(id: String, index: Int) = edit { it.move(id, index) }

    override fun moveUp(id: String) = edit { it.moveUp(id) }

    override fun moveDown(id: String) = edit { it.moveDown(id) }

    override fun remove(id: String) = edit { it.remove(id) }

    override fun add() = edit { it.add() }

    override fun reset() = edit { it.reset() }

    override fun arrangePins(order: List<String>) = edit { it.arrangePins(order) }

    private fun edit(change: (HomeLayout) -> HomeLayout) {
        viewModelScope.launch {
            try {
                home.edit(change)
            } catch (e: IOException) {
                messages.show(R.string.home_edit_failed, retry = { edit(change) })
            } catch (e: MaException) {
                messages.show(failureText(e), retry = ::reloadFailed)
            }
        }
    }

    /** Reloads only the sections that failed to load after an edit. */
    private fun reloadFailed() {
        viewModelScope.launch {
            try {
                home.reloadFailed()
            } catch (e: MaException) {
                messages.show(failureText(e), retry = ::reloadFailed)
            }
        }
    }

    private companion object {
        const val EDITING = "editing"
    }
}

internal data class HomeInputs(
    val layout: HomeLayout?,
    val items: Map<SectionOption, SectionItems>?,
    val offline: Boolean,
    /** The kinds the index is syncing for the first time. */
    val firstSyncs: Set<MediaType>,
    val speakers: HomeSpeakers = HomeSpeakers(),
)

// Speakers reduced to their names, so a song or a volume step on any speaker does not rebuild Home.
@Immutable
data class HomeSpeakers(val all: List<SpeakerUi> = emptyList(), val inUse: SpeakerUi? = null) {
    fun named(id: String): SpeakerUi? = inUse?.takeIf { it.id == id } ?: all.firstOrNull { it.id == id }

    companion object {
        fun of(all: List<SpeakerUi>, inUse: SpeakerUi?) = HomeSpeakers(all.map { it.nameOnly() }, inUse?.nameOnly())

        private fun SpeakerUi.nameOnly() = SpeakerUi(id, name, isPhone)
    }
}

// Only a failed connection counts: while restoring or connecting the skeleton shows, as "Can't reach
// Music Assistant" would be wrong then.
internal fun isOffline(session: SessionState): Boolean = session is SessionState.Offline

// Pins are filtered and ordered by the layout, so an unpin or a move shows before the section
// reloads.
internal fun homeState(inputs: HomeInputs, editing: Boolean, media: (HomeItem) -> MediaUi): HomeUiState {
    val layout = inputs.layout ?: HomeLayout()
    val sections = layout.sections.map { section ->
        val option = section.option
        val cached = inputs.items?.get(option)
        val list = cached?.items.orEmpty().let { list ->
            if (option == SectionOption.Pinned) {
                list.filter { layout.isPinned(it.uri) }.sortedBy { layout.pinned.indexOf(it.uri) }
            } else {
                list
            }
        }
        val noPins = option == SectionOption.Pinned && layout.pinned.isEmpty()
        val waitingForIndex = list.isEmpty() && option.indexKind in inputs.firstSyncs
        // Search is a field and never loads.
        val loading =
            !inputs.offline && option != SectionOption.Search && !noPins && (cached == null || waitingForIndex)
        // Edit mode shows every pin, so each can be arranged.
        val shown = if (editing &&
            option == SectionOption.Pinned
        ) {
            list.size
        } else {
            section.layout.shown(list.size, option)
        }
        SectionUi(
            id = section.id,
            option = option,
            layout = section.layout,
            items = list.take(shown).map(media),
            more = list.size > shown,
            loading = loading,
            fallback = cached?.fallback == true,
            title = section.title,
            titled = section.showsTitle,
            heardOn = cached?.speaker?.let(inputs.speakers::named),
        )
    }
    return HomeUiState(
        sections = sections,
        editing = editing,
        offline = inputs.offline,
    )
}

@Immutable
data class SectionUi(
    val id: String,
    val option: SectionOption,
    val layout: SectionLayout,
    /** Already cut to what the layout shows. */
    val items: List<MediaUi> = emptyList(),
    /** The option has more than the layout shows: See all. */
    val more: Boolean = false,
    val loading: Boolean = false,
    /** Recently played found nothing, so it shows the library's albums ("Your albums"). */
    val fallback: Boolean = false,
    /** The user's own name for the section; null for the option's. */
    val title: String? = null,
    /** Its heading shows outside edit mode. */
    val titled: Boolean = option != SectionOption.Search,
    /** Played here's speaker, named after the title. */
    val heardOn: SpeakerUi? = null,
) {
    val isEmpty: Boolean get() = option != SectionOption.Search && !loading && items.isEmpty()
}

@Immutable
data class HomeUiState(
    /** Empty sections included; see [visible]. */
    val sections: List<SectionUi> = emptyList(),
    val editing: Boolean = false,
    /** Changes the screen only when nothing is cached. */
    val offline: Boolean = false,
    /** Null until the index answers, which is asked only while [showsNothing]. */
    val libraryEmpty: Boolean? = null,
    val spoken: SpokenWord = SpokenWord(),
) {
    val visible: List<SectionUi> = if (editing) sections else sections.filterNot { it.isEmpty }

    val showsNothing: Boolean = !editing && visible.isEmpty() && !offline && sections.isNotEmpty()

    val hasContent: Boolean = sections.any { it.items.isNotEmpty() }

    // TalkBack hears "Loading" once for all sections; never while editing, where each section's
    // chips and Add section must stay reachable.
    val loading: Boolean = !editing && visible.isNotEmpty() && visible.all { it.loading }
}
