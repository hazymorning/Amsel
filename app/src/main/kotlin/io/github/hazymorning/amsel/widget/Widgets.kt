package io.github.hazymorning.amsel.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.playback.PhoneNotReadyException
import io.github.hazymorning.amsel.playback.failureText
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Amsel's widgets: drawn anew whenever what they show changes, while Amsel follows the music and a
 * widget is on the home screen, and never on a timer; and their taps.
 */
class Widgets(
    private val context: Context,
    private val store: WidgetStore,
    private val images: ImageLoader,
    private val scope: CoroutineScope,
    private val hands: () -> WidgetHands,
    /** The book or episode heard last, from the server. */
    private val lastStarted: suspend () -> WidgetItem?,
    /**
     * What the widgets show, and what failed to say on them: a command from the app, the
     * notification or a widget that did not reach its speaker. Asked for once there is a widget.
     */
    private val source: () -> Pair<Flow<WidgetLook>, Flow<String>>,
) {
    private val manager = AppWidgetManager.getInstance(context)
    private val provider = ComponentName(context, AmselWidget::class.java)
    private val views = WidgetViews(context)
    private val drawing = Mutex()
    private var following: Job? = null

    private val _look = MutableStateFlow<WidgetLook?>(null)

    /** What the widgets show now; null until Amsel has drawn them in this run. */
    val look: StateFlow<WidgetLook?> = _look.asStateFlow()

    private val _cover = MutableStateFlow<WidgetCover?>(null)
    val cover: StateFlow<WidgetCover?> = _cover.asStateFlow()

    /** A tap that went wrong, said on the widgets while they show what they showed then. */
    private val said = MutableStateFlow<Pair<WidgetLook, String>?>(null)

    private var coverArt: String? = null

    /** The cover last written to the phone, so a pause does not write it again. */
    private var storedCover: WidgetCover? = null
    private val faces = ConcurrentHashMap<String, Bitmap>()

    /** The widgets on the home screen. */
    fun ids(): IntArray = manager.getAppWidgetIds(provider)

    /** Draws what plays on the widgets from now on, while there are any. */
    @Synchronized
    fun follow() {
        if (following?.isActive == true || ids().isEmpty()) return
        val (looks, failures) = source()
        following = scope.launch {
            launch {
                failures.collect { text -> _look.value?.let { said.value = it.withoutFailure() to text } }
            }
            combine(looks, said) { look, failure -> look.copy(failure = failure?.takeIf { it.first == look }?.second) }
                .distinctUntilChanged()
                .collectLatest { draw(it) }
        }
    }

    /** The last widget left the home screen. */
    @Synchronized
    fun removed() {
        following?.cancel()
        following = null
    }

    /** Widgets [ids] need drawing, Amsel just started: as they showed, at rest, if Amsel follows no music yet. */
    suspend fun redraw(ids: IntArray) {
        val shown = _look.value
        if (shown != null) return draw(shown, ids)
        val stored = store.look()?.copy(playing = false, failure = null) ?: WidgetLook()
        val cover = store.cover()?.let(::rested)
        draw(stored, ids, cover)
    }

    /** Widget [id]'s buttons are set: it is drawn with them at once. */
    suspend fun setUp(id: Int, slots: List<WidgetSlot>) {
        store.setSlots(id, slots)
        redraw(intArrayOf(id))
        follow()
    }

    suspend fun slots(id: Int): List<WidgetSlot> = store.slots(id)

    /** For the setup page's picture, before Amsel has drawn the widgets in this run: what they showed last, at rest. */
    suspend fun showStored() {
        if (_look.value != null) return
        _cover.value = store.cover()?.let(::rested)
        _look.value = store.look()?.copy(playing = false, failure = null) ?: WidgetLook()
    }

    suspend fun forget(ids: IntArray): Unit = store.forget(ids.toList())

    /** Signing out: the widgets show none of what the last person played. */
    suspend fun forgetLook() = drawing.withLock {
        store.forgetLook()
        _cover.value = null
        coverArt = null
        storedCover = null
    }

    /** Button [slot] of widget [widgetId] was tapped while it showed [shownPlaying]. Main thread. */
    suspend fun tap(widgetId: Int, slot: Int, shownPlaying: Boolean) {
        val button = store.slots(widgetId).getOrNull(slot) ?: return
        // Amsel may have been closed: from now on the widgets show what the speaker reports.
        follow()
        val hands = hands()
        hands.wake()
        try {
            when (button.button) {
                WidgetButton.PlayPause -> if (shownPlaying) hands.pause() else hands.play()

                WidgetButton.Next -> hands.next()

                WidgetButton.Previous -> hands.previous()

                WidgetButton.Quieter -> hands.volume(up = false)

                WidgetButton.Louder -> hands.volume(up = true)

                WidgetButton.ContinueListening -> lastStarted()?.let { hands.play(it) }
                    ?: say(context.getString(R.string.widget_nothing_to_continue))

                WidgetButton.PlayItem -> button.item?.let { hands.play(it) }

                // Opens Amsel itself (WidgetViews).
                WidgetButton.Speakers -> Unit
            }
        } catch (e: MaException) {
            say(context.getString(failureText(e, play = true)))
        } catch (e: PhoneNotReadyException) {
            say(context.getString(failureText(e, play = true)))
        }
    }

    private fun say(text: String) {
        val shown = _look.value ?: return
        said.value = shown.withoutFailure() to text
        scope.launch { draw(shown.copy(failure = text)) }
    }

    private suspend fun draw(look: WidgetLook): Unit = draw(look, ids(), coverFor(look.art))

    private suspend fun draw(look: WidgetLook, ids: IntArray, cover: WidgetCover?): Unit = drawing.withLock {
        _look.value = look
        _cover.value = cover
        for (id in ids) {
            val slots = store.slots(id)
            val drawn = views.build(id, look, slots, cover, thumbs(slots))
            try {
                manager.updateAppWidget(id, drawn)
            } catch (e: RuntimeException) {
                // A launcher gone or an update Android refuses costs the widget one picture, never
                // the music: this runs in the app's own scope.
            }
        }
        if (!look.signedIn) {
            store.forgetLook()
            storedCover = null
            return@withLock
        }
        store.setLook(look.withoutFailure())
        if (cover !== storedCover) {
            store.setCover(cover?.bitmap)
            storedCover = cover
        }
    }

    private suspend fun draw(look: WidgetLook, ids: IntArray): Unit = draw(look, ids, coverFor(look.art))

    /** The blurred cover for [art], kept while it stays; null when there is none or it cannot be read. */
    private suspend fun coverFor(art: String?): WidgetCover? {
        if (art == null) return null
        if (art == coverArt) _cover.value?.let { return it }
        val bitmap = load(art, COVER_PX) ?: return null
        return withContext(Dispatchers.Default) { WidgetArt.cover(bitmap) }.also { coverArt = art }
    }

    /** The faces of [slots]' Play … buttons, by their cover's address. */
    suspend fun thumbs(slots: List<WidgetSlot>): Map<String, Bitmap> = slots
        .mapNotNull { it.item?.art }
        .distinct()
        .mapNotNull { art -> thumb(art)?.let { art to it } }
        .toMap()

    private suspend fun thumb(art: String): Bitmap? {
        faces[art]?.let { return it }
        val made = store.thumb(art) ?: load(art, THUMB_LOAD_PX)?.let { source ->
            val density = context.resources.displayMetrics.density
            val px = (THUMB_DP * density).toInt()
            withContext(Dispatchers.Default) { WidgetArt.thumb(source, px, density, THUMB_DP) }.also {
                store.setThumb(art, it)
            }
        }
        return made?.also { faces[art] = it }
    }

    private suspend fun load(url: String, px: Int): Bitmap? {
        val request = ImageRequest.Builder(context).data(url).size(px).allowHardware(false).build()
        return (images.execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    /** A cover kept from an earlier run, with its tone worked out again. */
    private fun rested(bitmap: Bitmap): WidgetCover {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return WidgetCover(bitmap, WidgetArt.inkReads(pixels, bitmap.width, bitmap.height))
    }

    private fun WidgetLook.withoutFailure() = copy(failure = null)

    private companion object {
        /** A Play … button's cover, as the layout sizes it. */
        const val THUMB_DP = 28f
        const val THUMB_LOAD_PX = 128
    }
}
