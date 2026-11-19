package io.github.hazymorning.amsel.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt

/** The pictures a widget shows, made beforehand off the main thread; bitmaps by their side in px. */
@Immutable
class WidgetPictures(
    /** The blurred cover of what plays, under the Cover look. */
    val cover: WidgetCover? = null,
    /** What plays, sharp, in Amsel's cover corners: a bar's or a player's cover, or a tile's without buttons. */
    val art: Map<Int, Bitmap> = emptyMap(),
    /** The Play … buttons' faces, by their cover's address. */
    val faces: Map<String, Bitmap> = emptyMap(),
    /** A one-cell Play … button's cover, filling its tile. */
    val tiles: Map<Int, Bitmap> = emptyMap(),
    /** The cover's shape, empty, where nothing plays. */
    val blanks: Map<Int, Bitmap> = emptyMap(),
)

/**
 * A widget's views at each of its sizes, laid out by [widgetFit] and placed by the ink: the words'
 * first letters and the speaker's glyph, the buttons' glyphs and the covers stand at the insets, not
 * their boxes. Colours go by resource, so the launcher switches day and night itself.
 */
class WidgetViews(private val context: Context) {
    internal val text = WidgetText(context)
    private val slots = WidgetSlots(context)
    private val density get() = context.resources.displayMetrics.density

    /** What TalkBack says for a button, and what the widget's page calls it. */
    fun label(slot: WidgetSlot, look: WidgetLook): String = slots.label(slot, look)

    /** How a widget [width] by [height] dp lays out [setup], every button of it, as its page counts them. */
    fun fit(width: Float, height: Float, setup: WidgetSetup): WidgetFit =
        widgetFit(width, height, setup.slots.size, text.type)

    /** How it lays out what [look] shows: a button with nothing to act on there gives way; its time shows. */
    fun fit(width: Float, height: Float, setup: WidgetSetup, look: WidgetLook): WidgetFit =
        widgetFit(width, height, setup.slots.count { it.button.availableIn(look) }, text.type, look.hasTime)

    /**
     * Widget [id] at each of [fits]: from Android 12 the launcher shows the one for its size; before,
     * the first is its portrait and the second its landscape. [taps] false for the page's picture.
     */
    fun build(
        id: Int,
        setup: WidgetSetup,
        look: WidgetLook,
        fits: List<WidgetFit>,
        pictures: WidgetPictures,
        clearInk: Boolean,
        taps: Boolean = true,
    ): RemoteViews {
        val drawing = WidgetDrawing(context, text, slots, id, setup, look, pictures, clearInk, taps)
        val sizes = fits.distinctBy { it.width to it.height }.take(MAX_SIZES)
        return when {
            sizes.size == 1 -> drawing.views(sizes[0])

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                RemoteViews(sizes.associate { SizeF(it.width, it.height) to drawing.views(it) })

            else -> RemoteViews(drawing.views(sizes[1]), drawing.views(sizes[0]))
        }
    }

    private companion object {
        /** A launcher keeps at most 16 sizes of one widget. */
        const val MAX_SIZES = 16
    }

    /** [dp] in pixels for a padding, which is never less than none, or a picture's side. */
    internal fun px(dp: Float): Int = (dp * density).roundToInt().coerceAtLeast(0)
}
