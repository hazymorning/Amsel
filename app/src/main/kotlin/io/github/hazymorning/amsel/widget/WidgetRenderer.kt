package io.github.hazymorning.amsel.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class WidgetRenderer(private val context: Context, private val images: ImageLoader) {
    val views = WidgetViews(context)
    private val manager = AppWidgetManager.getInstance(context)
    private val density get() = context.resources.displayMetrics.density

    private val shaped = object : LruCache<String, Bitmap>(SHAPED_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val blanks = HashMap<Int, Bitmap>()

    private val grounds = LruCache<String, WidgetCover>(GROUNDS)

    /** In dp. Before Android 12: portrait (min width, max height), then landscape, as [WidgetViews.build] needs. */
    fun sizes(id: Int, kind: WidgetKind): List<SizeF> {
        val options = manager.getAppWidgetOptions(id) ?: return listOf(kind.start)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listed = BundleCompat.getParcelableArrayList(
                options,
                AppWidgetManager.OPTION_APPWIDGET_SIZES,
                SizeF::class.java,
            )
            if (!listed.isNullOrEmpty()) return listed
        }
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat()
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat()
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).toFloat()
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).toFloat()
        if (minWidth <= 0f || maxHeight <= 0f) return listOf(kind.start)
        return listOf(SizeF(minWidth, maxHeight), SizeF(maxWidth, minHeight))
    }

    /** [taps] false draws without click intents; [clearInk] null asks the wallpaper. */
    suspend fun draw(
        id: Int,
        setup: WidgetSetup,
        look: WidgetContent,
        sizes: List<SizeF>,
        taps: Boolean = true,
        clearInk: Boolean? = null,
    ): RemoteViews {
        val fits = sizes.map { views.fit(it.width, it.height, setup, look) }
        val pictures = pictures(setup, look, fits, faces(setup, fits))
        val ink = setup.style == WidgetStyle.Clear && (clearInk ?: wallpaperWantsDarkText(context))
        return views.build(id, setup, look, fits, pictures, ink, taps)
    }

    private suspend fun pictures(
        setup: WidgetSetup,
        look: WidgetContent,
        fits: List<WidgetFit>,
        faces: Map<String, Bitmap>,
    ): WidgetBitmaps {
        val playing = look.signedIn && look.title != null
        val covered = fits.filter { it.form in Covered }.map { views.px(it.art) }
        val tiles = fits.filter { it.form == WidgetForm.Tile }
        val bare = tiles.filter { it.buttons == 0 }.map { views.px(minOf(it.width, it.height)) }
        val art = if (!playing) emptyMap() else pictured(look.artLarge ?: look.art, (covered + bare).distinct())
        val item = setup.shown(look, 1).firstOrNull()?.value
            ?.takeIf { it.button == WidgetButton.PlayItem }?.item?.art
        val buttoned = tiles.filter { it.buttons > 0 }.map { views.px(minOf(it.width, it.height)) }
        val tileArt = pictured(item, buttoned.distinct())
        // Blank shapes: the empty cover, and the night tint's mask over every cover shown.
        val shapes = covered + art.keys + tileArt.keys
        val ground = when {
            !playing -> null
            setup.style == WidgetStyle.Cover -> ground(look.art, COVER_PX, WidgetArt::cover)
            setup.style == WidgetStyle.Darkened -> ground(look.artLarge ?: look.art, ART_PX, WidgetArt::darkened)
            else -> null
        }
        return WidgetBitmaps(
            cover = ground,
            art = art,
            faces = faces,
            tiles = tileArt,
            blanks = shapes.distinct().associateWith(::blank),
        )
    }

    private suspend fun faces(setup: WidgetSetup, fits: List<WidgetFit>): Map<String, Bitmap> {
        val bare = setup.cells == WidgetCells.None
        val cells = fits.mapNotNull { cellSize(it, bare) }.map { (w, h) -> views.px(w) to views.px(h) }.distinct()
        val arts = setup.slots.filter { it.button == WidgetButton.PlayItem }.mapNotNull { it.item?.art }.distinct()
        return buildMap {
            for (art in arts) {
                for ((width, height) in cells) shaped(art, width, height)?.let { put(faceKey(art, width, height), it) }
            }
        }
    }

    private suspend fun pictured(url: String?, sides: List<Int>): Map<Int, Bitmap> {
        if (url == null) return emptyMap()
        return sides.mapNotNull { side -> shaped(url, side)?.let { side to it } }.toMap()
    }

    private suspend fun shaped(url: String, width: Int, height: Int = width): Bitmap? {
        val key = faceKey(url, width, height)
        shaped.get(key)?.let { return it }
        val source = load(url, maxOf(width, height, MIN_SOURCE_PX)) ?: return null
        val made = withContext(Dispatchers.Default) { WidgetArt.shaped(source, width, density, height) }
        shaped.put(key, made)
        return made
    }

    private fun blank(side: Int): Bitmap = synchronized(blanks) {
        blanks.getOrPut(side) { WidgetArt.blank(side, density) }
    }

    private suspend fun ground(url: String?, px: Int, make: (Bitmap) -> WidgetCover): WidgetCover? {
        if (url == null) return null
        val key = "$url@$px"
        grounds.get(key)?.let { return it }
        val bitmap = load(url, px) ?: return null
        val made = withContext(Dispatchers.Default) { make(bitmap) }
        grounds.put(key, made)
        return made
    }

    private suspend fun load(url: String, px: Int): Bitmap? {
        val request = ImageRequest.Builder(context).data(url).size(px).allowHardware(false).build()
        return (images.execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    /** On sign-out: no cover of the previous user's music stays in memory; blanks hold none. */
    fun forget() {
        shaped.evictAll()
        grounds.evictAll()
    }

    private companion object {
        /** About four sharp covers at two sizes each. */
        const val SHAPED_BYTES = 8 * 1024 * 1024

        /** The current and the previous background for each of the two cover styles. */
        const val GROUNDS = 4

        /** A small cover is scaled down from at least this, which keeps the picture sharp. */
        const val MIN_SOURCE_PX = 128
    }
}

/** The forms with a sharp cover beside or above the text. */
private val Covered = setOf(WidgetForm.Bar, WidgetForm.Player, WidgetForm.Poster)
