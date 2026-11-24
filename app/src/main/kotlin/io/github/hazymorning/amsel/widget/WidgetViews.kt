package io.github.hazymorning.amsel.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt

/** Made off the main thread before the views are built; maps keyed by side in px, [faces] by [faceKey]. */
@Immutable
class WidgetBitmaps(
    /** The background cover: blurred (Cover) or sharp (Darkened). */
    val cover: WidgetCover? = null,
    /** Sharp covers: a bar's, a player's or a poster's, and a tile's without buttons. */
    val art: Map<Int, Bitmap> = emptyMap(),
    val faces: Map<String, Bitmap> = emptyMap(),
    /** A one-cell Play … button's cover. */
    val tiles: Map<Int, Bitmap> = emptyMap(),
    /** The blank cover shape: the empty cover, and the mask for the night tint. */
    val blanks: Map<Int, Bitmap> = emptyMap(),
)

internal fun faceKey(art: String, width: Int, height: Int): String = "$art@${width}x$height"

class WidgetViews(private val context: Context) {
    internal val text = WidgetTextMetrics(context)
    private val slots = WidgetSlots(context)
    private val density get() = context.resources.displayMetrics.density

    fun label(slot: WidgetSlot, look: WidgetContent): String = slots.label(slot, look)

    /** Counts every button, as the widget's page does. */
    fun fit(width: Float, height: Float, setup: WidgetSetup): WidgetFit = fit(width, height, setup, setup.slots.size)

    /** Counts the buttons available in [look]; none while signed out. */
    fun fit(width: Float, height: Float, setup: WidgetSetup, look: WidgetContent): WidgetFit {
        val buttons = if (look.signedIn) setup.slots.count { it.button.availableIn(look) } else 0
        return fit(width, height, setup, buttons, look.hasTime)
    }

    private fun fit(width: Float, height: Float, setup: WidgetSetup, buttons: Int, time: Boolean = false) =
        widgetFit(width, height, buttons, text.type, time, setup.showsWords, bare = setup.cells == WidgetCells.None)

    /** Before Android 12, [fits] holds portrait then landscape, as [WidgetRenderer.sizes] lists them. */
    fun build(
        id: Int,
        setup: WidgetSetup,
        look: WidgetContent,
        fits: List<WidgetFit>,
        pictures: WidgetBitmaps,
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
        /** RemoteViews(Map) takes at most 16 sizes. */
        const val MAX_SIZES = 16
    }

    internal fun px(dp: Float): Int = (dp * density).roundToInt().coerceAtLeast(0)
}
