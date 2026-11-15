package io.github.hazymorning.amsel.ui.theme

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.hazymorning.amsel.ui.model.ArtRef
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A cover's dominant hue, the one colour the interface ever borrows: muted strongly, it tints Now
 * Playing's page by a few percent. Artwork may tint, never paint.
 */
@Immutable
data class CoverColors(val hue: Float, val saturation: Float) {
    /**
     * [surface] washed with the cover's hue: plainly coloured, yet quiet enough to read on. By day
     * a shade deeper than the paper, at night a shade lighter than the dark, so the hue shows.
     */
    fun tint(surface: Color, night: Boolean): Color = tint(surface, night, 1f)

    /**
     * A page's head under its cover: the same hue as [tint], plainer to see, as it covers only the
     * top of a page that scrolls away, where Now Playing's tint lies under all of it.
     */
    fun wash(surface: Color, night: Boolean): Color = tint(surface, night, WASH_STRENGTH)

    private fun tint(surface: Color, night: Boolean, by: Float): Color {
        val lightness = with(surface) { (maxOf(red, green, blue) + minOf(red, green, blue)) / 2 } +
            by * if (night) NIGHT_TINT_LIFT else -DAY_TINT_DEPTH
        val saturation = (TINT_SATURATION * strength * by).coerceAtMost(1f)
        return lerp(surface, hsl(hue, saturation, lightness.coerceIn(0f, 1f)), TINT_SHARE)
    }

    /** Covers with little colour lend little: a grey-blue sleeve tints less than a red one. */
    private val strength: Float get() = (saturation / FULL_SATURATION).coerceIn(0.4f, 1f)
}

/** Below this the cover counts as black and white, and the page stays plain. */
private const val GREY = 0.12f
private const val MIN_CHROMA = 0.035f
private const val FULL_SATURATION = 0.45f

private const val TINT_SATURATION = 0.55f
private const val TINT_SHARE = 0.7f
private const val DAY_TINT_DEPTH = 0.035f
private const val NIGHT_TINT_LIFT = 0.04f

/** How much plainer a page's wash is than Now Playing's tint. */
private const val WASH_STRENGTH = 1.6f

/** Covers are sampled at this size: plenty for a palette, and cheap to decode. */
private const val SAMPLE_PX = 64

/** Hues per cover, so Now Playing and every page share one extraction. */
private val cache = LruCache<String, Hue>(KEPT_HUES)

/** About 25 kB on the phone: a library's worth of covers that were opened. */
private const val KEPT_HUES = 512

/**
 * The hues found, kept on the phone: a cover sampled once lends its hue at once ever after, also
 * after Amsel restarts, so a page opens already washed. [load] reads them at the app's start, off
 * the main thread; each new hue is written a moment after it was found, with any others that came.
 */
object CoverHues {
    private const val FILE = "cover_hues.txt"
    private const val SAVE_DELAY_MS = 1_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writing = Mutex()
    private val pending = AtomicBoolean(false)

    @Volatile private var file: File? = null

    fun load(context: Context) {
        val kept = File(context.filesDir, FILE)
        file = kept
        scope.launch {
            val lines = runCatching { if (kept.exists()) kept.readLines() else emptyList() }.getOrDefault(emptyList())
            // Oldest first, as written, so the most recent stay longest.
            lines.forEach { line ->
                val parts = line.split('\t')
                val hue = when {
                    parts.size == 2 && parts[1] == GREY_MARK -> Hue.Grey

                    parts.size == 3 -> {
                        val h = parts[1].toFloatOrNull()
                        val sat = parts[2].toFloatOrNull()
                        if (h != null && sat != null) Hue.Of(CoverColors(h, sat)) else null
                    }

                    else -> null
                }
                if (hue != null && cache.get(parts[0]) == null) cache.put(parts[0], hue)
            }
        }
    }

    internal fun keep(key: String, hue: Hue) {
        cache.put(key, hue)
        if (file == null || !pending.compareAndSet(false, true)) return
        scope.launch {
            delay(SAVE_DELAY_MS)
            pending.set(false)
            save()
        }
    }

    private suspend fun save() = writing.withLock {
        val kept = file ?: return@withLock
        val text = cache.snapshot().entries
            .filter { (key, _) -> '\t' !in key && '\n' !in key }
            .joinToString("\n") { (key, hue) ->
                when (hue) {
                    is Hue.Of -> "$key\t${hue.colors.hue}\t${hue.colors.saturation}"
                    Hue.Grey -> "$key\t$GREY_MARK"
                }
            }
        runCatching {
            val next = File(kept.parentFile, "$FILE.new")
            next.writeText(text)
            next.renameTo(kept)
        }
    }

    private const val GREY_MARK = "-"
}

/** A cover's hue, or [Grey] when it has none worth borrowing. */
internal sealed interface Hue {
    data class Of(val colors: CoverColors) : Hue

    data object Grey : Hue
}

/**
 * Loads a small software bitmap of [art] with Coil and takes its dominant hue, or null when there is
 * no cover, it cannot be read, or it is black and white. The previous cover's hue stays while the
 * next one loads, so nothing flashes.
 */
@Composable
fun rememberCoverColors(art: ArtRef?): CoverColors? {
    val context = LocalContext.current
    val key = art?.base
    val colors by produceState(initialValue = key?.let { (cache.get(it) as? Hue.Of)?.colors }, key) {
        if (art == null || key == null) {
            value = null
            return@produceState
        }
        cache.get(key)?.let {
            value = (it as? Hue.Of)?.colors
            return@produceState
        }
        val loader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context)
            .data(art.sized(SAMPLE_PX))
            .size(SAMPLE_PX)
            .allowHardware(false)
            .build()
        val result = loader.execute(request) as? SuccessResult
        if (result == null) {
            // Not cached: the next time this cover is shown, it is tried again.
            value = null
            return@produceState
        }
        // Off the main thread in the app; tests run Coil on an immediate dispatcher, so this does too.
        val hue = withContext(loader.defaults.decoderCoroutineContext) {
            val palette = Palette.from(result.image.toBitmap())
                .maximumColorCount(16)
                // Palette's default filter also drops hues 10–37° (it guards photos of skin), which
                // would leave brown and orange covers with no colour at all. Only skip near-black
                // and near-white.
                .clearFilters()
                .addFilter { _, hsl -> hsl[2] in 0.05f..0.95f }
                .generate()
            hueOf(palette)
        }
        CoverHues.keep(key, hue)
        value = (hue as? Hue.Of)?.colors
    }
    return colors
}

/**
 * The cover's dominant hue: each coloured area weighs by its size and how colourful it is, and the
 * strongest family of neighbouring hues wins. A pale sky or a dusty sleeve still has a hue; a cover
 * whose colour is too faint overall counts as black and white.
 */
private fun hueOf(palette: Palette): Hue {
    val swatches = palette.swatches
    val total = swatches.sumOf { it.population }.toFloat().coerceAtLeast(1f)
    val weight = FloatArray(HUE_BINS)
    val x = FloatArray(HUE_BINS)
    val y = FloatArray(HUE_BINS)
    val saturation = FloatArray(HUE_BINS)
    var colour = 0f
    for (swatch in swatches) {
        val (h, s, l) = swatch.hsl
        val chroma = s * (1f - abs(2f * l - 1f))
        if (s < GREY || chroma < MIN_CHROMA) continue
        val w = swatch.population * chroma
        val bin = (h / (360f / HUE_BINS)).toInt().coerceIn(0, HUE_BINS - 1)
        val angle = Math.toRadians(h.toDouble())
        weight[bin] += w
        x[bin] += w * kotlin.math.cos(angle).toFloat()
        y[bin] += w * kotlin.math.sin(angle).toFloat()
        saturation[bin] += w * s
        colour += w
    }
    if (colour / total < MIN_COLOUR) return Hue.Grey
    fun family(b: Int) = (-1..1).map { (b + it + HUE_BINS) % HUE_BINS }
    val best = (0 until HUE_BINS).maxBy { b -> family(b).sumOf { weight[it].toDouble() } }
    val bins = family(best)
    val w = bins.sumOf { weight[it].toDouble() }.toFloat()
    val hue = Math.toDegrees(atan2(bins.sumOf { y[it].toDouble() }, bins.sumOf { x[it].toDouble() })).toFloat()
    return Hue.Of(CoverColors((hue + 360f) % 360f, bins.sumOf { saturation[it].toDouble() }.toFloat() / w))
}

/** Hue families of 30°, judged three at a time so a family split by a boundary still counts whole. */
private const val HUE_BINS = 12

/** Below this average colourfulness a cover counts as black and white. */
private const val MIN_COLOUR = 0.03f

internal fun hsl(hue: Float, saturation: Float, lightness: Float) =
    Color.hsl(hue, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))
