package io.github.hazymorning.amsel.data.home

import android.util.AtomicFile
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.MaJson
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** What Home last showed, per option, for one server: painted from disk before anything else. */
@Serializable
internal data class HomeSnapshot(val serverId: String, val sections: Map<SectionOption, SectionItems>)

/** What Home reads and changes: [HomeRepository] in the app, a fake in tests. */
interface HomeSource {
    val layout: StateFlow<HomeLayout?>
    val items: StateFlow<Map<SectionOption, SectionItems>?>

    /**
     * Each option Home has just loaded or changed. Home keeps only its first items, so this is how
     * a page of all of it learns that more than those may have changed.
     */
    val changes: Flow<SectionOption>

    suspend fun edit(change: (HomeLayout) -> HomeLayout)

    /** Reloads every section. */
    suspend fun refresh()

    /** Loads again only the options whose last load failed, as a Retry after an edit. */
    suspend fun reloadFailed()
}

/**
 * Home: the person's layout for the signed-in server ([HomeLayoutStore]) and the last items of
 * every option on it. Each section paints from its snapshot on launch, then refreshes; the
 * snapshot is keyed by option, so a section that only changes its layout redraws at once.
 */
class HomeRepository(
    private val sources: SectionLoader,
    private val layouts: HomeLayoutStore,
    file: File,
    private val serverIds: Flow<String?>,
    scope: CoroutineScope,
    /** The speaker music plays on: Played here loads again for each new one. */
    speakers: Flow<String?> = emptyFlow(),
) : HomeSource {
    private val _layout = MutableStateFlow<HomeLayout?>(null)
    private val snapshot = AtomicFile(file)

    /**
     * The layout of the server in the preferences, null until read. Home is its only writer, so
     * it is read once per server and an edit shows here at once, before it is stored.
     */
    override val layout: StateFlow<HomeLayout?> = _layout.asStateFlow()

    private val layoutEdits = Mutex()

    init {
        scope.launch {
            serverIds.distinctUntilChanged().collectLatest { id ->
                _layout.value = if (id == null) HomeLayout() else layouts.layout(id)
            }
        }
    }

    private val _items = MutableStateFlow<Map<SectionOption, SectionItems>?>(null)

    private val _changes =
        MutableSharedFlow<SectionOption>(
            extraBufferCapacity = SectionOption.entries.size,
            onBufferOverflow = DROP_OLDEST,
        )

    override val changes: Flow<SectionOption> = _changes.asSharedFlow()

    /** Each option's last items; null until something has been cached (the very first run). */
    override val items: StateFlow<Map<SectionOption, SectionItems>?> = _items.asStateFlow()

    private val writes = Mutex()

    /** Options whose last load failed ([reloadFailed]). */
    private val failed: MutableSet<SectionOption> = ConcurrentHashMap.newKeySet()

    private val _loaded = MutableStateFlow(false)

    /** [load] has run: the splash stays until then, so Home's first frame is the person's own. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        // After the snapshot, which only paints while nothing else has.
        scope.launch {
            speakers.distinctUntilChanged().collect { speaker ->
                loaded.first { it }
                val current = layout.filterNotNull().first()
                val shown = _items.value?.get(SectionOption.PlayedHere)
                if (current.sections.any { it.option == SectionOption.PlayedHere } && shown?.speaker != speaker) {
                    loadAll(current, setOf(SectionOption.PlayedHere), reshuffle = false)
                }
            }
        }
    }

    /** Reads the layout and the snapshot; Home's first frame waits for this, not for the server. */
    suspend fun load() {
        try {
            layout.filterNotNull().first()
            val id = serverIds.first() ?: return
            if (_items.value != null) return
            val snapshot = withContext(Dispatchers.IO) {
                try {
                    MaJson.decodeFromString(HomeSnapshot.serializer(), snapshot.readFully().decodeToString())
                } catch (e: IOException) {
                    null
                } catch (e: SerializationException) {
                    // Written by an earlier version: Home loads afresh.
                    null
                }
            }
            if (snapshot?.serverId == id) _items.compareAndSet(null, snapshot.sections)
        } finally {
            _loaded.value = true
        }
    }

    /**
     * Loads every option on Home, side by side; one that fails keeps what it had, and the first
     * failure is thrown once all are done. Random albums stay as they are: they change only out of
     * sight ([reshuffle]).
     */
    override suspend fun refresh() {
        val current = layout.filterNotNull().first()
        load(current, current.sections.map { it.option }.toSet(), reshuffle = false)
    }

    /** New random albums for the next visit to Home, picked while it is out of sight, never under the person's eyes. */
    suspend fun reshuffle() {
        val current = layout.filterNotNull().first()
        if (current.sections.any { it.option == SectionOption.RandomAlbums }) {
            load(current, setOf(SectionOption.RandomAlbums), reshuffle = true)
        }
    }

    override suspend fun reloadFailed() {
        val current = layout.filterNotNull().first()
        load(current, current.sections.map { it.option }.filter { it in failed }.toSet(), reshuffle = false)
    }

    /** Re-reads the options that come from the library index, after it has synced. */
    suspend fun refreshIndexed() {
        val current = layout.filterNotNull().first()
        load(current, current.sections.map { it.option }.filter { it.readsIndex }.toSet(), reshuffle = false)
    }

    /**
     * Changes the layout; options new to Home load at once. What leaves Home is forgotten: brought
     * back, it loads afresh rather than showing what it had then.
     */
    override suspend fun edit(change: (HomeLayout) -> HomeLayout) {
        val next = changeLayout(change) ?: return
        val shown = next.sections.map { it.option }.toSet()
        val left = _items.value.orEmpty().keys - shown
        if (left.isNotEmpty()) {
            _items.update { it?.minus(left) }
            serverIds.first()?.let { save(it) }
        }
        load(next, shown - _items.value.orEmpty().keys, reshuffle = false)
    }

    /** Shows [change] at once, then stores it; a failed write puts the layout back and throws. */
    private suspend fun changeLayout(change: (HomeLayout) -> HomeLayout): HomeLayout? = layoutEdits.withLock {
        val id = serverIds.first() ?: return null
        val before = layout.filterNotNull().first()
        val next = change(before)
        _layout.value = next
        try {
            layouts.save(id, next)
        } catch (e: IOException) {
            _layout.value = before
            throw e
        }
        next
    }

    fun isPinned(uri: String): Boolean = layout.value?.isPinned(uri) == true

    /** [uri] is finished: off Continue listening at once; the next load agrees. */
    fun finished(uri: String) {
        val listening = _items.value?.get(SectionOption.ContinueListening)
        if (listening == null || listening.items.none { it.uri == uri }) return
        _items.update { sections ->
            sections?.plus(
                SectionOption.ContinueListening to listening.copy(items = listening.items.filterNot { it.uri == uri }),
            )
        }
        _changes.tryEmit(SectionOption.ContinueListening)
    }

    /** Continue listening joins a Home made before it existed, once (HomeLayout.offerListening). */
    suspend fun offerListening() {
        if (layout.filterNotNull().first().listeningOffered) return
        val next = changeLayout { it.offerListening() } ?: return
        load(next, setOf(SectionOption.ContinueListening), reshuffle = false)
    }

    suspend fun pin(uri: String) = editPins { it.pin(uri) }

    suspend fun unpin(uri: String) = editPins { it.unpin(uri) }

    /** Up to [limit] items of [option], for its See all page; not cached. */
    suspend fun all(option: SectionOption, limit: Int): SectionItems =
        sources.load(option, limit, layout.value?.pinned.orEmpty(), _items.value?.get(option))

    /** Forgets the snapshot after signing out; layouts stay, so signing back in finds the same Home. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        writes.withLock {
            _items.value = null
            snapshot.delete()
        }
    }

    /** The pin is the change asked for; a Pinned section that cannot reload keeps what it showed. */
    private suspend fun editPins(change: (HomeLayout) -> HomeLayout) {
        val next = changeLayout(change) ?: return
        loadAll(next, setOf(SectionOption.Pinned), reshuffle = false)
    }

    private suspend fun load(layout: HomeLayout, options: Set<SectionOption>, reshuffle: Boolean) {
        loadAll(layout, options, reshuffle).firstOrNull()?.let { throw it }
    }

    /** Loads [options] side by side and returns what failed. */
    private suspend fun loadAll(layout: HomeLayout, options: Set<SectionOption>, reshuffle: Boolean): List<Exception> {
        if (options.isEmpty()) return emptyList()
        val id = serverIds.first() ?: return emptyList()
        val failures = coroutineScope {
            options.map { option -> async { load(layout, option, reshuffle) } }.awaitAll().filterNotNull()
        }
        save(id)
        return failures
    }

    /** Loads one option; on failure it keeps what it had and returns the failure. */
    private suspend fun load(layout: HomeLayout, option: SectionOption, reshuffle: Boolean): Exception? {
        val previous = _items.value?.get(option)
        if (option == SectionOption.RandomAlbums && !reshuffle && !previous?.items.isNullOrEmpty()) return null
        // New random albums do not go on from the old ones.
        val from = previous.takeUnless { option == SectionOption.RandomAlbums }
        val (loaded, failure) = try {
            sources.load(option, SNAPSHOT_ITEMS, layout.pinned, from) to null
        } catch (e: MaConnectionException) {
            // A first load that fails shows the section as empty rather than loading for ever.
            (previous ?: SectionItems()) to e
        } catch (e: MaCommandException) {
            (previous ?: SectionItems()) to e
        }
        _items.update { (it ?: emptyMap()) + (option to loaded) }
        if (failure == null) {
            failed -= option
            _changes.tryEmit(option)
        } else {
            failed += option
        }
        return failure
    }

    private suspend fun save(serverId: String) = withContext(Dispatchers.IO) {
        writes.withLock {
            val sections = _items.value ?: return@withLock
            val text = MaJson.encodeToString(HomeSnapshot.serializer(), HomeSnapshot(serverId, sections))
            // The snapshot only speeds up the next launch, which then paints the older one.
            val out = try {
                snapshot.startWrite()
            } catch (e: IOException) {
                return@withLock
            }
            try {
                out.write(text.encodeToByteArray())
                snapshot.finishWrite(out)
            } catch (e: IOException) {
                snapshot.failWrite(out)
            }
        }
    }

    companion object {
        /** One more than any layout shows, so a section knows when to offer See all. */
        const val SNAPSHOT_ITEMS = 13
    }
}
