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

/**
 * Draws a widget: at each size the launcher gives it, with the pictures each size needs, made off the
 * main thread from Amsel's own cover cache and kept while they are wanted again.
 */
internal class WidgetRender(private val context: Context, private val images: ImageLoader) {
    val views = WidgetViews(context)
    private val manager = AppWidgetManager.getInstance(context)
    private val density get() = context.resources.displayMetrics.density

    private val shaped = object : LruCache<String, Bitmap>(SHAPED_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val blanks = HashMap<Pair<Int, Boolean>, Bitmap>()

    /** The grounds of the covers shown, blurred or darkened, kept while they stay. */
    private val grounds = LruCache<String, WidgetCover>(GROUNDS)

    /**
     * The sizes widget [id] stands at, in dp: from Android 12 as the launcher lists them; before, its
     * portrait and then its landscape; [kind]'s starting size until the launcher says.
     */
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

    /**
     * Widget [id] with [setup], showing [look], at [sizes]; [taps] false for its page's picture. A
     * clear widget's words take ink where the wallpaper wants it ([clearInk], asked of Android unless given).
     */
    suspend fun draw(
        id: Int,
        setup: WidgetSetup,
        look: WidgetLook,
        sizes: List<SizeF>,
        taps: Boolean = true,
        clearInk: Boolean? = null,
    ): RemoteViews {
        val faces = faces(setup)
        val fits = sizes.map { views.fit(it.width, it.height, setup, look) }
        val pictures = pictures(setup, look, fits, faces)
        val ink = setup.style == WidgetStyle.Clear && (clearInk ?: wallpaperWantsInk(context))
        return views.build(id, setup, look, fits, pictures, ink, taps)
    }

    /** The covers [fits] show: the ground, what plays at each cover's side, a tile's Play … cover. */
    private suspend fun pictures(
        setup: WidgetSetup,
        look: WidgetLook,
        fits: List<WidgetFit>,
        faces: Map<String, Bitmap>,
    ): WidgetPictures {
        val playing = look.signedIn && look.title != null
        val covered = fits.filter { it.form in Covered }.map { views.px(it.art) }
        val tiles = fits.filter { it.form == WidgetForm.Tile }
        val bare = tiles.filter { it.buttons == 0 }.map { views.px(minOf(it.width, it.height)) }
        val art = if (!playing) emptyMap() else pictured(look.artLarge ?: look.art, (covered + bare).distinct())
        val item = setup.shown(look, 1).firstOrNull()?.value
            ?.takeIf { it.button == WidgetButton.PlayItem }?.item?.art
        val buttoned = tiles.filter { it.buttons > 0 }.map { views.px(minOf(it.width, it.height)) }
        val tileArt = pictured(item, buttoned.distinct(), round = true)
        // The cover's shape: empty where nothing plays, and at night over every cover that fills a side.
        val shapes = covered + art.keys
        val ground = when {
            !playing -> null
            setup.style == WidgetStyle.Cover -> ground(look.art, COVER_PX, WidgetArt::cover)
            setup.style == WidgetStyle.Darkened -> ground(look.artLarge ?: look.art, ART_PX, WidgetArt::darkened)
            else -> null
        }
        return WidgetPictures(
            cover = ground,
            art = art,
            faces = faces,
            tiles = tileArt,
            blanks = shapes.distinct().associateWith { blank(it, round = false) },
            rounds = tileArt.keys.associateWith { blank(it, round = true) },
        )
    }

    /** The faces of [setup]'s Play … buttons, round, by their cover's address. */
    private suspend fun faces(setup: WidgetSetup): Map<String, Bitmap> {
        val side = views.px(WidgetSizes.FACE)
        return setup.slots
            .filter { it.button == WidgetButton.PlayItem }
            .mapNotNull { it.item?.art }
            .distinct()
            .mapNotNull { art -> shaped(art, side, round = true)?.let { art to it } }
            .toMap()
    }

    private suspend fun pictured(url: String?, sides: List<Int>, round: Boolean = false): Map<Int, Bitmap> {
        if (url == null) return emptyMap()
        return sides.mapNotNull { side -> shaped(url, side, round)?.let { side to it } }.toMap()
    }

    /** The cover at [url], [side] px square in Amsel's cover corners, or [round]; null when it cannot be read. */
    private suspend fun shaped(url: String, side: Int, round: Boolean = false): Bitmap? {
        val key = "$url@$side${if (round) " round" else ""}"
        shaped.get(key)?.let { return it }
        val source = load(url, maxOf(side, MIN_SOURCE_PX)) ?: return null
        val made = withContext(Dispatchers.Default) {
            if (round) WidgetArt.round(source, side, density) else WidgetArt.shaped(source, side, density)
        }
        shaped.put(key, made)
        return made
    }

    private fun blank(side: Int, round: Boolean): Bitmap = synchronized(blanks) {
        blanks.getOrPut(side to round) { WidgetArt.blank(side, density, round) }
    }

    /** The cover at [url], [px] square, laid down by [make]; null when there is none or it cannot be read. */
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

    /** Signing out: no cover of the last person's music stays in memory. */
    fun forget() {
        shaped.evictAll()
        grounds.evictAll()
    }

    private companion object {
        /** About four players' and tiles' covers at two sizes each. */
        const val SHAPED_BYTES = 8 * 1024 * 1024

        /** What plays now under each look, and what played before while it changes. */
        const val GROUNDS = 4

        /** A small face is made from a larger picture, so its corners and hairline stay crisp. */
        const val MIN_SOURCE_PX = 128
    }
}

/** The forms that show what plays sharp, beside or above its words. */
private val Covered = setOf(WidgetForm.Bar, WidgetForm.Player, WidgetForm.Poster)
