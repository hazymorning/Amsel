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
import io.github.hazymorning.amsel.ui.theme.Elevation
import io.github.hazymorning.amsel.ui.theme.HAIRLINE_ALPHA
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.elevationShadow
import io.github.hazymorning.amsel.ui.theme.isNight
import io.github.hazymorning.amsel.ui.theme.rememberPress

private val LargeGlyphFrom = 128.dp

/**
 * Decorative: the enclosing row or tile carries the label. Without a picture: a type cover of [name],
 * else [glyph] or the kind's icon. [interactionSource] only draws an enclosing clickable's press;
 * [shape] is for a container's corners (a card), never a circle.
 */
@Composable
fun Artwork(
    art: ArtRef?,
    modifier: Modifier = Modifier,
    kind: MediaKind = MediaKind.Album,
    dimmed: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape? = null,
    elevated: Boolean = false,
    name: String? = null,
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
                .then(if (elevated) Modifier.elevationShadow(shape, Elevation.Cover) else Modifier)
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
                    AlignedGlyph(glyph ?: fallbackIcon(kind), tint = colors.outline, size = fallbackGlyph(side))
                }
            }
            val tone = if (night) nightTone(colors.surfaceContainerLow, colors.onSurface) else null
            if (art != null && art.quarters.isNotEmpty()) {
                Quarters(art.quarters, side, tone)
            } else if (art != null) {
                val px = with(LocalDensity.current) { side.roundToPx() }.coerceAtLeast(1)
                val context = LocalContext.current
                // Placeholder: the last cover shown, so a cover changed in place (the next song) crossfades from it.
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

/** Shared with [PreloadCover]: the preload only hits the memory cache if both build the same request. */
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

/** Another size of [art] already in memory (its tile's), so a page opens with its cover in place. */
private fun smallerCopy(context: Context, art: ArtRef, px: Int): MemoryCache.Key? {
    if (px <= TILE_PX || art.mosaic || art.base.startsWith("file:")) return null
    val cache = SingletonImageLoader.get(context).memoryCache ?: return null
    val sized = "${art.base}?size="
    return cache.keys.firstOrNull { it.key.startsWith(sized) }
}

/** The largest size a tile requests; only a larger cover can find a smaller copy in memory. */
private const val TILE_PX = 512

/**
 * Maps a cover's channels linearly onto [floor]..[ceiling] at night: black stays distinct from the
 * page and white is no brighter than the text.
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

/** ColorMatrix offsets are on a 0..255 scale, not 0..1. */
private const val CHANNEL = 255f

/** Not snapshot state: only the next request reads it, so a load recomposes nothing. */
private class ShownCover {
    var key: MemoryCache.Key? = null
}

/** Clips to [shape] with a hairline inside its edge, so a white cover keeps its edge on a light page. */
@Composable
fun Modifier.coverFrame(shape: Shape): Modifier {
    val edge = MaterialTheme.colorScheme.onSurface.copy(alpha = HAIRLINE_ALPHA)
    return clip(shape).drawWithCache {
        // Inset by half the stroke so the whole hairline lies inside the clip.
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
