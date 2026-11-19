package io.github.hazymorning.amsel.widget

import android.app.WallpaperManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SizeF
import android.widget.RemoteViews
import coil3.ImageLoader
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.playback.PhoneNotReadyException
import io.github.hazymorning.amsel.playback.failureText
import io.github.hazymorning.amsel.widget.WidgetStore.Companion.AMSELS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the widgets show, from Amsel. */
interface WidgetSource {
    /** What plays where Amsel plays. */
    val looks: Flow<WidgetLook>

    /** What plays on speaker [id], for a widget of its own. */
    fun lookOf(id: String): Flow<WidgetLook>

    /** Commands that did not reach their speaker, from the app, the notification or a widget: its id, and the sentence. */
    val failures: Flow<Pair<String, String>>
}

/**
 * Amsel's widgets: drawn anew whenever what they show changes, while Amsel follows the music and a
 * widget is on the home screen, and never on a timer; and their taps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Widgets(
    private val context: Context,
    private val store: WidgetStore,
    images: ImageLoader,
    private val scope: CoroutineScope,
    /** Hands for a tap: on the speaker Amsel plays on (null), or on a widget's own. */
    private val hands: (speaker: String?) -> WidgetHands,
    /** The book or episode heard last, from the server. */
    private val lastStarted: suspend () -> WidgetItem?,
    /** Asked for once there is a widget. */
    private val source: () -> WidgetSource,
    /** Amsel follows the music in this run: a widget placed now follows it at once. */
    private val live: () -> Boolean = { false },
    actions: WidgetActions = WidgetActions.None,
) {
    private val states = StateTaps(actions, SystemClock::elapsedRealtime)
    private val previews =
        WidgetPreviews(context) { kind, look -> render.draw(0, kind.setup, look, listOf(kind.start), false) }
    private val manager = AppWidgetManager.getInstance(context)
    private val render = WidgetRender(context, images)
    private val drawing = Mutex()
    private var following: Job? = null

    val views: WidgetViews get() = render.views

    private val setups = MutableStateFlow<Map<Int, WidgetSetup>>(emptyMap())
    private val _looks = MutableStateFlow<Map<String, WidgetLook>?>(null)

    /** What the widgets show now, by speaker ([AMSELS] for Amsel's); null until Amsel drew them in this run. */
    val looks: StateFlow<Map<String, WidgetLook>?> = _looks.asStateFlow()

    /** A tap that went wrong, said on the widgets while they show what they showed then. */
    private val said = MutableStateFlow<Pair<WidgetLook, String>?>(null)

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
        following = scope.launch {
            loadSetups()
            launch { source.failures.collect(::sayFailure) }
            setups.map { all -> all.values.mapNotNull { it.speaker }.toSet() }
                .distinctUntilChanged()
                .flatMapLatest { fixed -> looksOf(source, fixed) }
                .combine(said, ::withFailure)
                .map { steadyClocks(it, _looks.value) }
                .distinctUntilChanged()
                .collectLatest { draw(it) }
        }
        listenToWallpaper(true)
    }

    private fun sayFailure(failure: Pair<String, String>) {
        val (speaker, text) = failure
        val shown = _looks.value?.values?.firstOrNull { it.speakerId == speaker } ?: return
        said.value = shown.withoutFailure() to text
    }

    /** [looks], the one a tap went wrong on with its sentence. */
    private fun withFailure(looks: Map<String, WidgetLook>, failure: Pair<WidgetLook, String>?) =
        looks.mapValues { (_, look) -> if (look == failure?.first) look.copy(failure = failure.second) else look }

    /** A clear widget's words take ink or paper by the wallpaper: they are drawn again when it changes. */
    private val wallpaperChanged = WallpaperManager.OnColorsChangedListener { _, which ->
        val clear = setups.value.values.any { it.style == WidgetStyle.Clear }
        if (clear && which and WallpaperManager.FLAG_SYSTEM != 0) {
            scope.launch { drawing.withLock { _looks.value?.let { drawLocked(it) } } }
        }
    }

    private fun listenToWallpaper(on: Boolean) {
        val wallpapers = WallpaperManager.getInstance(context)
        try {
            if (on) {
                wallpapers.addOnColorsChangedListener(wallpaperChanged, Handler(Looper.getMainLooper()))
            } else {
                wallpapers.removeOnColorsChangedListener(wallpaperChanged)
            }
        } catch (e: RuntimeException) {
            // A phone without a wallpaper service: a clear widget follows its wallpaper at the next song.
        }
    }

    private fun looksOf(source: WidgetSource, fixed: Set<String>): Flow<Map<String, WidgetLook>> {
        val flows = listOf(source.looks.map { AMSELS to it }) + fixed.map { id -> source.lookOf(id).map { id to it } }
        return combine(flows) { it.toMap() }
    }

    /** What plays on [speaker], or where Amsel plays: for a widget's page, before its speaker is saved. */
    fun lookFor(speaker: String?): Flow<WidgetLook> = if (speaker == null) source().looks else source().lookOf(speaker)

    /** The last widget left the home screen. */
    @Synchronized
    fun removed() {
        if (ids().isNotEmpty()) return
        following?.cancel()
        following = null
        listenToWallpaper(false)
    }

    /** Widgets [ids] need drawing (Amsel started, or a widget changed size): as they showed, at rest, if Amsel follows no music yet. */
    suspend fun redraw(ids: IntArray) {
        loadSetups()
        // Read under the lock: a look read before it could be older than one drawn meanwhile, and cover it.
        drawing.withLock {
            val looks = _looks.value ?: store.looks().mapValues { (_, look) -> look.atRest() }
            for (id in ids) drawOne(id, looks)
        }
        if (live()) follow()
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
    suspend fun picture(id: Int, setup: WidgetSetup, look: WidgetLook, size: SizeF): RemoteViews =
        render.draw(id, setup, look, listOf(size), taps = false)

    suspend fun forget(ids: IntArray) {
        store.forget(ids.toList())
        setups.update { it - ids.toSet() }
    }

    /** Signing out: the widgets show none of what the last person played. */
    suspend fun forgetLook() = drawing.withLock {
        store.forgetLooks()
        render.forget()
    }

    /** Button [slot] of widget [widgetId] was tapped while it showed [shownPlaying]. Main thread. */
    suspend fun tap(widgetId: Int, slot: Int, shownPlaying: Boolean) {
        val setup = setup(widgetId)
        val button = setup.slots.getOrNull(slot) ?: return
        // Amsel may have been closed: from now on the widgets show what the speaker reports.
        follow()
        val hands = hands(setup.speaker)
        hands.wake()
        try {
            when (button.button) {
                WidgetButton.PlayPause -> if (shownPlaying) hands.pause() else hands.play()

                WidgetButton.Next -> hands.next()

                WidgetButton.Previous -> hands.previous()

                WidgetButton.Quieter, WidgetButton.Louder, WidgetButton.Shuffle, WidgetButton.Repeat,
                WidgetButton.Favourite,
                -> (shown(setup)?.let { states.tap(button.button, it) } as? StateTaps.Refusal.AtLimit)
                    ?.let { say(setup, limitText(it.limit)) }

                WidgetButton.ContinueListening -> lastStarted()?.let { hands.play(it) }
                    ?: say(setup, context.getString(R.string.widget_nothing_to_continue))

                WidgetButton.PlayItem -> button.item?.let { hands.play(it) }

                // Open Amsel itself (WidgetCellsViews.press).
                WidgetButton.Speakers, WidgetButton.UpNext -> Unit
            }
        } catch (e: MaException) {
            say(setup, context.getString(failureText(e, play = true)))
        } catch (e: PhoneNotReadyException) {
            say(setup, context.getString(failureText(e, play = true)))
        }
    }

    /** What [setup]'s widget shows now, or showed when Amsel last drew it. */
    private suspend fun shown(setup: WidgetSetup): WidgetLook? =
        (_looks.value ?: store.looks())[setup.speaker ?: AMSELS]

    private fun limitText(limit: Int): String =
        context.getString(R.string.widget_at_limit, context.getString(R.string.player_volume_percent, limit))

    private fun say(setup: WidgetSetup, text: String) {
        val looks = _looks.value ?: return
        val shown = looks[setup.speaker ?: AMSELS] ?: return
        val failure = shown.withoutFailure() to text
        said.value = failure
        if (following?.isActive != true) scope.launch { draw(withFailure(looks, failure)) }
    }

    private suspend fun loadSetups() {
        setups.value = ids().associateWith { setup(it) }
    }

    private suspend fun draw(looks: Map<String, WidgetLook>): Unit = drawing.withLock { drawLocked(looks) }

    private suspend fun drawLocked(looks: Map<String, WidgetLook>) {
        _looks.value = looks
        for (id in ids()) drawOne(id, looks)
        looks[AMSELS]?.let { scope.launch { previews.offer(it) } }
        if (looks[AMSELS]?.signedIn == false) {
            store.forgetLooks()
            render.forget()
        } else {
            store.setLooks(looks.mapValues { (_, look) -> look.withoutFailure() })
        }
    }

    private suspend fun drawOne(id: Int, looks: Map<String, WidgetLook>) {
        val setup = setups.value[id] ?: setup(id)
        val look = looks[setup.speaker ?: AMSELS] ?: looks[AMSELS] ?: WidgetLook()
        val drawn = render.draw(id, setup, look, render.sizes(id, kindOf(id)))
        try {
            manager.updateAppWidget(id, drawn)
        } catch (e: RuntimeException) {
            // A launcher gone or an update Android refuses costs the widget one picture, never
            // the music: this runs in the app's own scope.
        }
    }

    private fun WidgetLook.withoutFailure() = copy(failure = null)

    private companion object {
        fun classFor(name: String): Class<*>? = Providers.keys.firstOrNull { it.name == name }
    }
}
