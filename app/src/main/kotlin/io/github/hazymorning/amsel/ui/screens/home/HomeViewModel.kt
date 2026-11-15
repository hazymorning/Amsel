package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.app.Messages
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

/** Edit mode's actions, so the screen can be drawn without a ViewModel. */
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

    /** The pins Home shows, in the order the person put them. */
    fun arrangePins(order: List<String>) {}

    object None : HomeEditor
}

/**
 * Home paints the cached sections at once (the first state is computed from them, so there is no
 * skeleton flash on launch) and follows them as they refresh. An edit shows at once: the changed
 * layout stands in for the stored one until the store has written it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val handle: SavedStateHandle,
    private val home: HomeSource,
    session: StateFlow<SessionState>,
    /** The library index is syncing: an index-backed section still empty on its first sync is loading. */
    indexSyncing: Flow<Boolean>,
    /** The kinds the index has synced at least once (LibraryRepository.synced). */
    indexSynced: Flow<Set<MediaType>>,
    /** The library index holds nothing (LibraryRepository.isEmpty). */
    libraryEmpty: Flow<Boolean>,
    private val mapper: UiMapper,
    private val messages: Messages,
    /** What the library index holds of spoken word: What to show offers its options only then. */
    spokenWord: Flow<SpokenWord> = flowOf(SpokenWord()),
    /** The speakers, for Speakers and to name the one Played here shows. */
    speakers: Flow<HomeSpeakers> = flowOf(HomeSpeakers()),
) : ViewModel(),
    HomeEditor {
    // Saved, so an edit sheet restored after process death opens in edit mode again.
    private val editing = handle.getStateFlow(EDITING, false)

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
            homeState(
                HomeInputs(
                    home.layout.value,
                    home.items.value,
                    isOffline(session.value),
                    firstSyncs = emptySet(),
                    // Not known before the flow answers: the Speakers row holds its room meanwhile.
                    speakers = HomeSpeakers(loaded = false),
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

    override fun arrangePins(order: List<String>) = edit { it.arrangePins(order) }

    private fun edit(change: (HomeLayout) -> HomeLayout) {
        viewModelScope.launch {
            try {
                home.edit(change)
            } catch (e: IOException) {
                messages.show(R.string.home_edit_failed, retry = { edit(change) })
            } catch (e: MaConnectionException) {
                messages.show(failureText(e), retry = ::reloadFailed)
            } catch (e: MaCommandException) {
                messages.show(failureText(e), retry = ::reloadFailed)
            }
        }
    }

    /** After an edit's new section failed to load: that section again, the others as they are. */
    private fun reloadFailed() {
        viewModelScope.launch {
            try {
                home.reloadFailed()
            } catch (e: MaConnectionException) {
                messages.show(failureText(e), retry = ::reloadFailed)
            } catch (e: MaCommandException) {
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

/**
 * Every speaker as Play on lists them, This phone first, and the one music plays on; [loaded] once
 * the server named them in this run of the app.
 */
@Immutable
data class HomeSpeakers(
    val all: List<SpeakerUi> = emptyList(),
    val inUse: SpeakerUi? = null,
    val loaded: Boolean = true,
) {
    fun named(id: String): SpeakerUi? = inUse?.takeIf { it.id == id } ?: all.firstOrNull { it.id == id }

    /**
     * Speakers: those that play or rest on something, where music can go on. The one in use is among
     * them, so speakers grouped under it still show, as the group they play in.
     */
    val shown: List<SpeakerUi> = all.filter { it.available && (it.isPlaying || it.song != null) }
}

/**
 * Only a failed connection counts as offline. Waiting (restoring, or connecting right after
 * Sign in) shows the skeleton: "Can't reach Music Assistant" would be wrong while it connects.
 */
internal fun isOffline(session: SessionState): Boolean = session is SessionState.Offline

/**
 * Home's state from the layout and each option's cached items. A section loads while nothing is
 * cached for its option, or while its option reads a kind the index syncs for the first time;
 * offline, nothing loads. Pins follow the layout at once, so an unpinned item leaves and a moved
 * one moves before the section reloads.
 */
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
        val ofSpeakers = option == SectionOption.Speakers
        // Speakers keep no snapshot: their row holds its room until the server names them. Search waits for nothing.
        val loading = !inputs.offline && option != SectionOption.Search &&
            if (ofSpeakers) !inputs.speakers.loaded else !noPins && (cached == null || waitingForIndex)
        // Edit mode shows every pin Home holds, so each can be put in its place.
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
            speakers = if (ofSpeakers) inputs.speakers.shown else emptyList(),
            inUse = inputs.speakers.inUse?.id,
        )
    }
    return HomeUiState(
        sections = sections,
        editing = editing,
        offline = inputs.offline,
    )
}

/** One sentence of Home, "Show [option] as [layout]", with the items its layout shows. */
@Immutable
data class SectionUi(
    val id: String,
    val option: SectionOption,
    val layout: SectionLayout,
    /** What the layout shows, already cut to its size. */
    val items: List<MediaUi> = emptyList(),
    /** The option has more than the layout shows: See all. */
    val more: Boolean = false,
    /** Nothing cached for it yet: its layout's skeleton. */
    val loading: Boolean = false,
    /** Recently played found nothing, so it shows the library's albums ("Your albums"). */
    val fallback: Boolean = false,
    /** The person's own name for it, if they gave one. */
    val title: String? = null,
    /** Its heading shows outside edit mode. */
    val titled: Boolean = option != SectionOption.Speakers && option != SectionOption.Search,
    /** Played here: the speaker whose plays it shows, named after its title. */
    val heardOn: SpeakerUi? = null,
    /** Speakers: those it shows; none away from them, which hides it. */
    val speakers: List<SpeakerUi> = emptyList(),
    /** The speaker music plays on. */
    val inUse: String? = null,
) {
    val isEmpty: Boolean get() = option != SectionOption.Search && !loading && items.isEmpty() && speakers.isEmpty()
}

/**
 * Everything Home draws. Each section paints from its snapshot; offline, the snapshot stays on
 * screen and the shell's message says why. Outside edit mode an empty section is hidden.
 */
@Immutable
data class HomeUiState(
    /** Every section in order, empty ones too (edit mode shows them). */
    val sections: List<SectionUi> = emptyList(),
    val editing: Boolean = false,
    /** Music Assistant can't be reached. Only changes the screen when nothing is cached. */
    val offline: Boolean = false,
    /** The library index has no music; null until it has answered, which only [showsNothing] asks. */
    val libraryEmpty: Boolean? = null,
    /** What to show offers the options of what the library holds of spoken word. */
    val spoken: SpokenWord = SpokenWord(),
) {
    /** The sections on screen: all of them while editing, otherwise those with something to show. */
    val visible: List<SectionUi> = if (editing) sections else sections.filterNot { it.isEmpty }

    /** Outside edit mode no section has anything to show, so an empty state says why. */
    val showsNothing: Boolean = !editing && visible.isEmpty() && !offline && sections.isNotEmpty()

    val hasContent: Boolean = sections.any { it.items.isNotEmpty() }

    /**
     * Every section on screen is still loading: TalkBack hears "Loading" once for all of them.
     * Never while editing, where each section's options and Add section must stay reachable.
     */
    val loading: Boolean = !editing && visible.isNotEmpty() && visible.all { it.loading }
}
