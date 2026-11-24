package io.github.hazymorning.amsel.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.SizeF
import android.widget.RemoteViews
import coil3.ImageLoader
import io.github.hazymorning.amsel.widget.WidgetStore.Companion.AMSELS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

interface WidgetSource {
    /** For the speaker Amsel plays on. */
    val content: Flow<WidgetContent>

    fun contentOf(id: String): Flow<WidgetContent>

    /** Failed commands from anywhere in Amsel, as (speaker id, message). */
    val failures: Flow<Pair<String, String>>
}

/** Redraws the widgets on every change while there are any; while the socket is closed, a job calls [refresh]. */
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetController(
    private val context: Context,
    private val store: WidgetStore,
    images: ImageLoader,
    private val scope: CoroutineScope,
    /** Null: the speaker Amsel plays on. */
    private val hands: (speaker: String?) -> WidgetTransport,
    /** The book or episode last in progress, for Continue listening. */
    private val lastStarted: suspend () -> WidgetItem?,
    /** Created only once a widget exists. */
    private val source: () -> WidgetSource,
    /** Whether [redraw] also starts following, so a widget placed now updates at once. */
    private val live: Boolean = false,
    actions: WidgetActions = WidgetActions.None,
    private val connected: () -> Boolean = { false },
    /** Reads the speakers over a socket of its own; false when the server did not answer. */
    private val refreshSpeakers: suspend () -> Boolean = { false },
    private val plan: WidgetRefreshScheduler = WidgetRefreshScheduler.None,
    elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val taps = WidgetTapHandler(context, StateButtonHandler(actions, elapsedRealtime), lastStarted)
    private val previews =
        WidgetPreviews(context) { kind, look -> render.draw(0, kind.setup, look, listOf(kind.start), false) }
    private val manager = AppWidgetManager.getInstance(context)
    private val render = WidgetRenderer(context, images)
    private val drawing = Mutex()
    private var following: Job? = null

    val views: WidgetViews get() = render.views

    private val setups = MutableStateFlow<Map<Int, WidgetSetup>>(emptyMap())
    private val _contents = MutableStateFlow<Map<String, WidgetContent>?>(null)

    /** By speaker id, [AMSELS] for the speaker Amsel plays on; null until the first draw in this process. */
    val contents: StateFlow<Map<String, WidgetContent>?> = _contents.asStateFlow()

    /** A failure message, shown only while the content equals the content it failed on. */
    private val said = MutableStateFlow<Pair<WidgetContent, String>?>(null)

    private val pending = MutableStateFlow(0)

    /** The periodic and the one-off job can run at once; one refresh is enough. */
    private val looking = Mutex()

    fun ids(): IntArray = Providers.keys.flatMap {
        manager.getAppWidgetIds(ComponentName(context, it)).asList()
    }.toIntArray()

    fun kindOf(id: Int): WidgetKind =
        Providers[manager.getAppWidgetInfo(id)?.provider?.className?.let(::classFor)] ?: WidgetKind.NowPlaying

    @Synchronized
    fun follow() {
        if (following?.isActive == true || ids().isEmpty()) return
        val source = source()
        plan.schedulePeriodic()
        following = scope.launch {
            loadSetups()
            launch { source.failures.collect(::sayFailure) }
            setups.map { all -> all.values.mapNotNull { it.speaker }.toSet() }
                .distinctUntilChanged()
                .flatMapLatest { fixed -> contentsOf(source, fixed) }
                .combine(said, ::withFailure)
                .map { steadyClocks(it, _contents.value) }
                .distinctUntilChanged()
                .collectLatest { looks ->
                    pending.update { it + 1 }
                    try {
                        draw(looks)
                    } finally {
                        pending.update { it - 1 }
                    }
                }
        }
        wallpaper.listen(true)
    }

    /** From [WidgetRefreshJobService]: reads the speakers unless the socket is open, and waits for the redraw. */
    suspend fun refresh() {
        if (ids().isEmpty()) return plan.cancelAll()
        follow()
        if (connected() || !looking.tryLock()) return
        try {
            refreshSpeakers()
            // Lets the new state reach the flows, then waits for the draws it starts, a fresh process's
            // first one included, before the job finishes.
            delay(PASS_ON_MS)
            withTimeoutOrNull(DRAWN_WITHIN_MS) {
                contents.first { it != null }
                pending.first { it == 0 }
            }
        } finally {
            looking.unlock()
        }
    }

    /** After a draw from stored content (a reboot, an app start, a placement): one refresh unless connected. */
    fun refreshSoon() {
        plan.schedulePeriodic()
        if (!connected()) plan.scheduleOnce()
    }

    private fun sayFailure(failure: Pair<String, String>) {
        val (speaker, text) = failure
        val shown = _contents.value?.values?.firstOrNull { it.speakerId == speaker } ?: return
        said.value = shown.withoutFailure() to text
    }

    private fun withFailure(looks: Map<String, WidgetContent>, failure: Pair<WidgetContent, String>?) =
        looks.mapValues { (_, look) -> if (look == failure?.first) look.copy(failure = failure.second) else look }

    /** A Clear widget's text colour follows the wallpaper. */
    private val wallpaper = WallpaperColorsListener(context) {
        if (setups.value.values.any { it.style == WidgetStyle.Clear }) {
            scope.launch { drawing.withLock { _contents.value?.let { drawLocked(it) } } }
        }
    }

    private fun contentsOf(source: WidgetSource, fixed: Set<String>): Flow<Map<String, WidgetContent>> {
        val flows =
            listOf(source.content.map { AMSELS to it }) + fixed.map { id -> source.contentOf(id).map { id to it } }
        return combine(flows) { it.toMap() }
    }

    fun contentFor(speaker: String?): Flow<WidgetContent> = if (speaker ==
        null
    ) {
        source().content
    } else {
        source().contentOf(speaker)
    }

    /** Called when the last widget is removed (onDisabled). */
    @Synchronized
    fun removed() {
        if (ids().isNotEmpty()) return
        following?.cancel()
        following = null
        plan.cancelAll()
        wallpaper.listen(false)
    }

    /** Before the first draw in this process, draws stored content as not playing ([WidgetContent.atRest]). */
    suspend fun redraw(ids: IntArray) {
        loadSetups()
        // Read under the lock: content read before it could be older than a draw made meanwhile, and overwrite it.
        drawing.withLock {
            val looks = _contents.value ?: store.contents().mapValues { (_, look) -> look.atRest() }
            for (id in ids) drawOne(id, looks)
        }
        if (live) follow()
    }

    suspend fun setUp(id: Int, setup: WidgetSetup) {
        store.setSetup(id, setup)
        setups.update { it + (id to setup) }
        redraw(intArrayOf(id))
        follow()
    }

    suspend fun setup(id: Int): WidgetSetup = store.setup(id) ?: kindOf(id).setup

    /** The portrait size: the tallest the launcher lists. */
    fun size(id: Int): SizeF = render.sizes(id, kindOf(id)).maxBy { it.height }

    suspend fun picture(id: Int, setup: WidgetSetup, look: WidgetContent, size: SizeF): RemoteViews =
        render.draw(id, setup, look, listOf(size), taps = false)

    suspend fun forget(ids: IntArray) {
        store.forget(ids.toList())
        setups.update { it - ids.toSet() }
    }

    /** On sign-out: nothing of the previous user's listening stays on disk or in memory. */
    suspend fun forgetContent() = drawing.withLock {
        store.forgetContents()
        render.forget()
    }

    /** [slot] is the button's index in the setup. Main thread only, as the transports require. */
    suspend fun tap(widgetId: Int, slot: Int, shownPlaying: Boolean) {
        val setup = setup(widgetId)
        val button = setup.slots.getOrNull(slot) ?: return
        // The tap may have started the process.
        follow()
        val shown = suspend { (_contents.value ?: store.contents())[setup.speaker ?: AMSELS] }
        taps.tap(button, hands(setup.speaker), shownPlaying, shown) { say(setup, it) }
    }

    private fun say(setup: WidgetSetup, text: String) {
        val looks = _contents.value ?: return
        val shown = looks[setup.speaker ?: AMSELS] ?: return
        val failure = shown.withoutFailure() to text
        said.value = failure
        if (following?.isActive != true) scope.launch { draw(withFailure(looks, failure)) }
    }

    private suspend fun loadSetups() {
        setups.value = ids().associateWith { setup(it) }
    }

    private suspend fun draw(looks: Map<String, WidgetContent>): Unit = drawing.withLock { drawLocked(looks) }

    private suspend fun drawLocked(looks: Map<String, WidgetContent>) {
        _contents.value = looks
        for (id in ids()) drawOne(id, looks)
        looks[AMSELS]?.let { scope.launch { previews.offer(it) } }
        if (looks[AMSELS]?.signedIn == false) {
            store.forgetContents()
            render.forget()
        } else {
            store.setContents(looks.mapValues { (_, look) -> look.withoutFailure() })
        }
    }

    private suspend fun drawOne(id: Int, looks: Map<String, WidgetContent>) {
        val setup = setups.value[id] ?: setup(id)
        val look = looks[setup.speaker ?: AMSELS] ?: looks[AMSELS] ?: WidgetContent()
        val drawn = render.draw(id, setup, look, render.sizes(id, kindOf(id)))
        try {
            manager.updateAppWidget(id, drawn)
        } catch (e: RuntimeException) {
            // A dead launcher or a refused update loses one draw; uncaught, it would crash the app's scope.
        }
    }

    private fun WidgetContent.withoutFailure() = copy(failure = null)

    private companion object {
        const val PASS_ON_MS = 300L

        /** A cover still loading after this is drawn at the next update. */
        const val DRAWN_WITHIN_MS = 10_000L

        fun classFor(name: String): Class<*>? = Providers.keys.firstOrNull { it.name == name }
    }
}
