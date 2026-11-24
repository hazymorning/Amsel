package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import io.github.hazymorning.amsel.testing.SampleCovers
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.DarkColors
import io.github.hazymorning.amsel.ui.theme.LightColors
import io.github.hazymorning.amsel.ui.theme.ThemeMode
import io.github.hazymorning.amsel.ui.theme.contrast
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class CoverShapeTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(DelicateCoilApi::class)
    @Before
    fun loader() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(context).components {
                add(SampleCovers())
            }.coroutineContext(Dispatchers.Unconfined).build(),
        )
    }

    @OptIn(DelicateCoilApi::class)
    @After
    fun reset() = SingletonImageLoader.reset()

    @Test fun noKindOfArtworkIsCutRound() {
        compose.setContent {
            AmselTheme(mode = ThemeMode.Light) {
                Column {
                    MediaKind.entries.forEach { kind -> Artwork(null, Modifier.size(Side).testTag(kind.name), kind) }
                }
            }
        }
        MediaKind.entries.forEach { kind ->
            assertTrue("$kind artwork is cut round", corner(kind.name).alpha > 0.9f)
        }
    }

    @Test fun everyKindSetInTypeKeepsItsCornersInARowAndOnAPage() {
        var shown by mutableStateOf(MediaKind.Album to RowCover)
        compose.setContent {
            AmselTheme(mode = ThemeMode.Light) {
                val (kind, side) = shown
                Artwork(null, Modifier.size(side).testTag(COVER), kind, name = NAME)
            }
        }
        for (kind in MediaKind.entries) {
            for (side in listOf(RowCover, PageCover)) {
                shown = kind to side
                assertTrue("$kind set in type at $side is cut round", corner(COVER).alpha > 0.9f)
            }
        }
    }

    @Test fun fourCoversTwoByTwoFillTheCornersOfOne() {
        var side by mutableStateOf(RowCover)
        compose.setContent {
            AmselTheme(mode = ThemeMode.Light) {
                Artwork(Quarters, Modifier.size(side).testTag(COVER), MediaKind.Artist)
            }
        }
        for (each in listOf(RowCover, PageCover)) {
            side = each
            val corner = corner(COVER)
            // A quarter clipped round would leave this point showing the bare background.
            assertTrue("four covers at $each are cut round", corner.alpha > 0.9f)
            assertTrue("four covers at $each leave their corner bare", corner != LightColors.surfaceContainerLow)
        }
    }

    @Test fun theLetterIsAToneOnTheCoverNotALabel() {
        var mode by mutableStateOf(ThemeMode.Light)
        compose.setContent {
            AmselTheme(mode = mode) {
                Artwork(null, Modifier.size(RowCover).testTag(COVER), MediaKind.Artist, name = NAME)
            }
        }
        mapOf(ThemeMode.Light to LightColors, ThemeMode.Dark to DarkColors).forEach { (theme, colors) ->
            mode = theme
            val paper = colors.surfaceContainerLow
            val letter = compose.onNodeWithTag(COVER).captureToImage().toPixelMap().strongest(paper, px(Inset))
            val ratio = contrast(letter, paper)
            assertTrue("$theme: no letter shows", ratio > 1.05f)
            assertTrue("$theme: the letter is %.2f:1, a label".format(ratio), ratio < 1.3f)
        }
    }

    /** The point [Inset] in from the top start corner, which a circle leaves empty and the cover's corners fill. */
    private fun corner(tag: String): Color {
        val inset = px(Inset)
        return compose.onNodeWithTag(tag).captureToImage().toPixelMap()[inset, inset]
    }

    private fun px(dp: Dp): Int = (dp.value * compose.density.density).toInt()
}

/** The colour with the most contrast to [paper], [clear] px in from every edge, past the hairline. */
private fun PixelMap.strongest(paper: Color, clear: Int): Color =
    (clear until width - clear).flatMap { x -> (clear until height - clear).map { y -> this[x, y] } }
        .maxBy { contrast(it, paper) }

private val Side = 72.dp
private val Inset = 8.dp
private val RowCover = 56.dp
private val PageCover = 272.dp
private const val COVER = "cover"
private const val NAME = "Flitz&Suppe"
private val Quarters = listOf("album_1", "album_2", "album_3", "album_4")
    .map { ArtRef("sample:$it") }
    .let { ArtRef(it.first().base, quarters = it) }
