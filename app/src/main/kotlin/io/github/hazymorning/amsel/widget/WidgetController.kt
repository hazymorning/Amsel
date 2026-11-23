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

/** What the widgets show, from Amsel. */
interface WidgetSource {
    /** What plays where Amsel plays. */
    val content: Flow<WidgetContent>

    /** What plays on speaker [id], for a widget of its own. */
    fun contentOf(id: String): Flow<WidgetContent>

    /** Commands that did not reach their speaker, from the app, the notification or a widget: its id, and the sentence. */
    val failures: Flow<Pair<String, String>>
}

/**
 * Amsel's widgets: drawn anew whenever what they show changes, while Amsel follows the music and a
 * widget is on the home screen; while its socket is closed they look at the speakers now and then
 * ([look]); and their taps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetController(
    private val context: Context,
    private val store: WidgetStore,
    images: ImageLoader,
    private val scope: CoroutineScope,
    /** Hands for a tap: on the speaker Amsel plays on (null), or on a widget's own. */
    private val hands: (speaker: String?) -> WidgetTransport,
    /** The book or episode heard last, from the server. */
    private val lastStarted: suspend () -> WidgetItem?,
    /** Asked for once there is a widget. */
    private val source: () -> WidgetSource,
    /** Amsel follows the music in this run: a widget placed now follows it at once. */
    private val live: Boolean = false,
    actions: WidgetActions = WidgetActions.None,
    /** Whether Amsel's own socket is connected: the widgets follow what it hears. */
    private val connected: () -> Boolean = { false },
    /** Reads what the speakers do on a glance while the socket is closed; false when the server did not answer. */
    private val refreshSpeakers: suspend () -> Boolean = { false },
    private val plan: WidgetRefreshScheduler = WidgetRefreshScheduler.None,
    elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private val taps = WidgetTapHandler(context, StateButtonHandler(actions, elapsedRealtime), lastStarted)
    private val previews =
        WidgetPreviews(context) { kind, look -> render.draw(0, kind.setup, look, listOf(kind.start), false) }
    private val manager = AppWidgetManager.getInstance(context)
    private val render = WidgetRender(context, images)
    private val drawing = Mutex()
    private var following: Job? = null

    val views: WidgetViews get() = render.views

    private val setups = MutableStateFlow<Map<Int, WidgetSetup>>(emptyMap())
    private val _contents = MutableStateFlow<Map<String, WidgetContent>?>(null)

    /** What the widgets show now, by speaker ([AMSELS] for Amsel's); null until Amsel drew them in this run. */
    val contents: StateFlow<Map<String, WidgetContent>?> = _contents.asStateFlow()

    /** A tap that went wrong, said on the widgets while they show what they showed then. */
    private val said = MutableStateFlow<Pair<WidgetContent, String>?>(null)

    /** Draws asked for and not done yet. */
    private val pending = MutableStateFlow(0)

    /** Android may run both looks at once; one is enough. */
    private val looking = Mutex()

    /** The widgets on the home screen, of all three kinds. */
    fun ids(): IntArray = Providers.keys.flatMap {
        manager.getAppWidgetIds(ComponentName(context, it)).asList()
    }.toIntArray()

    fun kindOf(id: Int): WidgetKind =
        Providers[manager.getAppWidgetInfo(id)?.provider?.className?.let(::classFor)] ?: WidgetKind.NowPlaying

    /** Draws what plays on the widgets from now on, while there are any. */
    @Synchronized
    fun follow() {
        if (following?.isActive == true || ids().isEmpty()) return
        val source = source()
        plan.keep()
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

    /** A look at the speakers while Amsel's socket is closed ([WidgetRefreshJobService]): read on a glance and drawn. */
    suspend fun refresh() {
        if (ids().isEmpty()) return plan.stop()
        follow()
        if (connected() || !looking.tryLock()) return
        try {
            refreshSpeakers()
            // What was read passes through the flows first; then every draw it asked for finishes,
            // the first in this run of Amsel too.
            delay(PASS_ON_MS)
            withTimeoutOrNull(DRAWN_WITHIN_MS) {
                contents.first { it != null }
                pending.first { it == 0 }
            }
        } finally {
            looking.unlock()
        }
    }

    /** Drawn at rest from what they showed (the phone or Amsel started anew, a widget placed): look once. */
    fun refreshSoon() {
        plan.keep()
        if (!connected()) plan.once()
    }

    private fun sayFailure(failure: Pair<String, String>) {
        val (speaker, text) = failure
        val shown = _contents.value?.values?.firstOrNull { it.speakerId == speaker } ?: return
        said.value = shown.withoutFailure() to text
    }

    /** [content], the one a tap went wrong on with its sentence. */
    private fun withFailure(looks: Map<String, WidgetContent>, failure: Pair<WidgetContent, String>?) =
        looks.mapValues { (_, look) -> if (look == failure?.first) look.copy(failure = failure.second) else look }

    /** A clear widget's words take ink or paper by the wallpaper: they are drawn again when it changes. */
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

    /** What plays on [speaker], or where Amsel plays: for a widget's page, before its speaker is saved. */
    fun contentFor(speaker: String?): Flow<WidgetContent> = if (speaker ==
        null
    ) {
        source().content
    } else {
        source().contentOf(speaker)
    }

    /** The last widget left the home screen. */
    @Synchronized
    fun removed() {
        if (ids().isNotEmpty()) return
        following?.cancel()
        following = null
        plan.stop()
        wallpaper.listen(false)
    }

    /** Widgets [ids] need drawing (Amsel started, or a widget changed size): as they showed, at rest, if Amsel follows no music yet. */
    suspend fun redraw(ids: IntArray) {
        loadSetups()
        // Read under the lock: a look read before it could be older than one drawn meanwhile, and cover it.
        drawing.withLock {
            val looks = _contents.value ?: store.contents().mapValues { (_, look) -> look.atRest() }
            for (id in ids) drawOne(id, looks)
        }
        if (live) follow()
    }

    /** Widget [id] is set up: it is drawn so at once. */
    suspend fun setUp(id: Int, setup: WidgetSetup) {
        store.setSetup(id, setup)
        setups.update { it + (id to setup) }
        redraw(intArrayOf(id))
        follow()
    }

    suspend fun setup(id: Int): WidgetSetup = store.setup(id) ?: kindOf(id).setup

    /** Widget [id]'s size in portrait, as the launcher gives it. */
    fun size(id: Int): SizeF = render.sizes(id, kindOf(id)).maxBy { it.height }

    /** Widget [id] as it would show [look] with [setup] at [size], for its page: nothing on it acts. */
    suspend fun picture(id: Int, setup: WidgetSetup, look: WidgetContent, size: SizeF): RemoteViews =
        render.draw(id, setup, look, listOf(size), taps = false)

    suspend fun forget(ids: IntArray) {
        store.forget(ids.toList())
        setups.update { it - ids.toSet() }
    }

    /** Signing out: the widgets show none of what the last person played. */
    suspend fun forgetContent() = drawing.withLock {
        store.forgetContents()
        render.forget()
    }

    /** Button [slot] of widget [widgetId] was tapped while it showed [shownPlaying]. Main thread. */
    suspend fun tap(widgetId: Int, slot: Int, shownPlaying: Boolean) {
        val setup = setup(widgetId)
        val button = setup.slots.getOrNull(slot) ?: return
        // Amsel may have been closed: from now on the widgets show what the speaker reports.
        follow()
        // What the widget shows now, or showed when Amsel last drew it.
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
            // A launcher gone or an update Android refuses costs the widget one picture, never
            // the music: this runs in the app's own scope.
        }
    }

    private fun WidgetContent.withoutFailure() = copy(failure = null)

    private companion object {
        const val PASS_ON_MS = 300L

        /** A cover still on its way after this is drawn the next time. */
        const val DRAWN_WITHIN_MS = 10_000L

        fun classFor(name: String): Class<*>? = Providers.keys.firstOrNull { it.name == name }
    }
}
