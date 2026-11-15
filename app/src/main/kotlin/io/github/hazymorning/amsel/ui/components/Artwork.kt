package io.github.hazymorning.amsel.ui.components

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.theme.DIM_ALPHA
import io.github.hazymorning.amsel.ui.theme.HAIRLINE_ALPHA
import io.github.hazymorning.amsel.ui.theme.Lift
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.isNight
import io.github.hazymorning.amsel.ui.theme.lift
import io.github.hazymorning.amsel.ui.theme.rememberPress

private val LargeGlyphFrom = 128.dp

/**
 * A cover in its continuous corners, the one shape every cover takes at its size, an artist's
 * picture as much as an album's (never a circle), with a hairline just inside its edge so a white sleeve never
 * bleeds into the page, and at night in the night's own range ([nightTone]). Without a picture it is set in
 * type from its [name]. Decorative: rows and tiles name what it shows. [dimmed] quiets something already played; a tile passes its
 * [interactionSource], so a press shows on the cover in its own shape. [shape] overrides the
 * outline where a container decides it (a card's corners).
 */
@Composable
fun Artwork(
    art: ArtRef?,
    modifier: Modifier = Modifier,
    kind: MediaKind = MediaKind.Album,
    dimmed: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape? = null,
    /** A page's or Now Playing's one large cover stands off the page on a soft shade. */
    lifted: Boolean = false,
    /** What it is called: without a picture, the cover is set in type ([TypeCover]). */
    name: String? = null,
    /** Without a picture or a [name], this glyph rather than its kind's. */
    @DrawableRes glyph: Int? = null,
) {
    BoxWithConstraints(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        val side = maxWidth
        val shape = shape ?: Shapes.cover(side)
        val colors = MaterialTheme.colorScheme
        val night = colors.isNight
        val press = rememberPress(shape, sink = false)
        Box(
            Modifier
                .fillMaxSize()
                .then(if (lifted) Modifier.lift(shape, Lift.Cover) else Modifier)
                .coverFrame(shape)
                .background(colors.surfaceContainerLow)
                .then(if (interactionSource != null) Modifier.indication(interactionSource, press) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            var failed by remember(art) { mutableStateOf(art == null) }
            if (failed) {
                if (name != null && monogramOf(name) != null) {
                    TypeCover(name, side)
                } else {
                    InkGlyph(glyph ?: fallbackIcon(kind), tint = colors.outline, size = fallbackGlyph(side))
                }
            }
            val tone = if (night) nightTone(colors.surfaceContainerLow, colors.onSurface) else null
            if (art != null && art.quarters.isNotEmpty()) {
                Quarters(art.quarters, side, tone)
            } else if (art != null) {
                val px = with(LocalDensity.current) { side.roundToPx() }.coerceAtLeast(1)
                val context = LocalContext.current
                // A cover that changes in place (the next song) fades in over the last one, not over the empty tone.
                val shown = remember { ShownCover() }
                val request = remember(art, px) {
                    coverRequest(context, art, px, shown.key ?: smallerCopy(context, art, px))
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = tone,
                    onError = { failed = true },
                    onSuccess = { success ->
                        failed = false
                        shown.key = success.result.memoryCacheKey
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (dimmed) Box(Modifier.fillMaxSize().background(colors.scrim.copy(alpha = DIM_ALPHA)))
        }
    }
}

/** Four covers two by two, edge to edge, in the cover's one outline. */
@Composable
private fun Quarters(parts: List<ArtRef>, side: Dp, tone: ColorFilter?) {
    val px = with(LocalDensity.current) { (side / 2).roundToPx() }.coerceAtLeast(1)
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        for (pair in parts.chunked(2)) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (part in pair) {
                    AsyncImage(
                        model = remember(part, px) { coverRequest(context, part, px) },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        colorFilter = tone,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * What a cover [px] wide loads, in one place: Now Playing's is asked for ahead ([PreloadCover]) with
 * the same request, so it is in the cache when the sheet rises. [placeholder] is the cover it replaces.
 */
internal fun coverRequest(context: Context, art: ArtRef, px: Int, placeholder: MemoryCache.Key? = null): ImageRequest {
    val load = if (art.mosaic) px * MOSAIC_SCALE else px
    return ImageRequest.Builder(context)
        .data(art.sized(load))
        .size(load)
        .placeholderMemoryCacheKey(placeholder)
        .crossfade(Motion.COVER_MS)
        .apply { if (art.mosaic) transformations(MosaicCrop(px)) }
        .build()
}

/**
 * A smaller copy of [art] already in memory, such as its tile's, for a larger cover [px] wide to
 * show until its own has loaded: a page opens with its cover in place, not an empty square.
 */
private fun smallerCopy(context: Context, art: ArtRef, px: Int): MemoryCache.Key? {
    if (px <= TILE_PX || art.mosaic || art.base.startsWith("file:")) return null
    val cache = SingletonImageLoader.get(context).memoryCache ?: return null
    val sized = "${art.base}?size="
    return cache.keys.firstOrNull { it.key.startsWith(sized) }
}

/** What a tile asks for at most: only a cover larger than that has a smaller copy to start from. */
private const val TILE_PX = 512

/**
 * A cover at night, drawn between the first surface's tone and the ink: its black stands a step
 * above the page, as an empty sleeve does, so a dark cover keeps its edge with no line drawn round
 * it, and its white is the words' light, so a white sleeve never glares. Every tone between keeps
 * its place; by day a cover is drawn as it is.
 */
@Composable
private fun nightTone(floor: Color, ceiling: Color): ColorFilter = remember(floor, ceiling) {
    fun channel(low: Float, high: Float) = (high - low) to low * CHANNEL
    val (r, rOffset) = channel(floor.red, ceiling.red)
    val (g, gOffset) = channel(floor.green, ceiling.green)
    val (b, bOffset) = channel(floor.blue, ceiling.blue)
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                r, 0f, 0f, 0f, rOffset,
                0f, g, 0f, 0f, gOffset,
                0f, 0f, b, 0f, bOffset,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

/** A colour matrix's offsets count in steps of a channel's 0 to 255. */
private const val CHANNEL = 255f

/** The cover last shown, kept out of snapshot state: only the next request reads it, so a load recomposes nothing. */
private class ShownCover {
    var key: MemoryCache.Key? = null
}

/** A cover's clip and its half-dp hairline in one outline, which everything drawn inside shares. */
@Composable
fun Modifier.coverFrame(shape: Shape): Modifier {
    val edge = MaterialTheme.colorScheme.onSurface.copy(alpha = HAIRLINE_ALPHA)
    return clip(shape).drawWithCache {
        // Just inside the outline, so it never shifts the layout.
        val width = HairlineWidth.toPx()
        val outline = shape.createOutline(Size(size.width - width, size.height - width), layoutDirection, this)
        val stroke = Stroke(width)
        onDrawWithContent {
            drawContent()
            translate(width / 2, width / 2) { drawOutline(outline, edge, style = stroke) }
        }
    }
}

private fun fallbackGlyph(side: Dp): Dp = if (side < LargeGlyphFrom) Sizes.Icon else Sizes.IconLarge

@DrawableRes
private fun fallbackIcon(kind: MediaKind): Int = when (kind) {
    MediaKind.Album -> R.drawable.ic_album
    MediaKind.Artist -> R.drawable.ic_artist
    MediaKind.Playlist -> R.drawable.ic_playlist
    MediaKind.Track -> R.drawable.ic_track
    MediaKind.Radio -> R.drawable.ic_radio
    MediaKind.Audiobook -> R.drawable.ic_audiobook
    MediaKind.Podcast, MediaKind.Episode -> R.drawable.ic_podcast
}
