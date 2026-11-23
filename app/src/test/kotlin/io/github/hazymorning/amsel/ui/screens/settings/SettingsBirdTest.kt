package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.DarkColors
import io.github.hazymorning.amsel.ui.theme.ThemeMode
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The blackbird at the foot of Settings stands on the page's end, never makes a page scroll that fits
 * without it, stays the README's picture and is a picture only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsBirdTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aPageThatScrollsEndsWithThePicture() = assertEquals(
        PicturePlacement(height = 2296, bird = 2072),
        picturePlacement(settings = 2000, bird = 224, gap = 72, end = 96, room = 1900),
    )

    @Test fun whereEverythingFitsThePictureStandsAtTheScreensFoot() = assertEquals(
        PicturePlacement(height = 2000, bird = 1776),
        picturePlacement(settings = 1000, bird = 224, gap = 72, end = 96, room = 2000),
    )

    @Test fun thePictureIsLeftOutWhereOnlyItWouldMakeThePageScroll() = assertEquals(
        PicturePlacement(height = 1200, bird = null),
        picturePlacement(settings = 1000, bird = 224, gap = 72, end = 96, room = 1200),
    )

    @Test fun aPageThatFitsToTheLastPixelStaysAsItIs() = assertEquals(
        PicturePlacement(height = 1096, bird = null),
        picturePlacement(settings = 1000, bird = 224, gap = 72, end = 96, room = 1096),
    )

    @Test fun aLongPageScrollsUntilTheCrownsStandOnItsEdge() {
        val scroll = show(room = 300.dp, settings = 400.dp)
        assertEquals(px(400.dp) + px(48.dp) + px(BirdHeight) - px(300.dp), scroll.maxValue)
        assertNotEquals("the picture shows before the page's end", DayTrees.front, footPixel())
        compose.runOnIdle { runBlocking { scroll.scrollTo(scroll.maxValue) } }
        assertEquals(DayTrees.front, footPixel())
    }

    @Test fun aShortPageHasThePictureAtItsFootAndDoesNotScroll() {
        val scroll = show(room = 600.dp, settings = 400.dp)
        assertEquals(0, scroll.maxValue)
        assertEquals(DayTrees.front, footPixel())
    }

    @Test fun aPageThatOnlyJustFitsShowsNoPictureAndDoesNotScroll() {
        val scroll = show(room = 450.dp, settings = 400.dp)
        assertEquals(0, scroll.maxValue)
        assertNotEquals(DayTrees.front, footPixel())
    }

    @Test fun thePictureSaysNothingToTalkBack() {
        compose.setContent { AmselTheme { SettingsBird() } }
        compose.onRoot().onChildren().assertCountEquals(0)
    }

    @Test fun thePictureKeepsTheReadmesMeasures() {
        val header = HeaderSvg("light")
        val (disc, back, behind, front) = header.circles
        assertEquals(ReadmeHeader.DISC, disc.radius, 0f)
        assertEquals(ReadmeHeader.FOOT, header.height - disc.y, 0f)
        assertEquals(ReadmeHeader.Back, back.from(disc))
        assertEquals(ReadmeHeader.Behind, behind.from(disc))
        assertEquals(ReadmeHeader.Front, front.from(disc))
        assertEquals(ReadmeHeader.Head, header.head - Offset(disc.x, disc.y))
        assertEquals(ReadmeHeader.ICON_UNIT, header.iconUnit, 0f)
        assertEquals(ReadmeHeader.IconHead, header.iconHead)
    }

    @Test fun theCrownsWearTheReadmesTints() {
        assertEquals(DayTrees, HeaderSvg("light").crowns())
        assertEquals(NightTrees, HeaderSvg("dark").crowns())
    }

    @Test fun atNightTheReadmesMoonIsTheInk() = assertEquals(
        DarkColors.onSurface,
        HeaderSvg("dark").circles.first().fill,
    )

    private fun show(room: Dp, settings: Dp): ScrollState {
        val scroll = ScrollState(0)
        compose.setContent {
            AmselTheme(mode = ThemeMode.Light) {
                PageWithBird(Modifier.testTag(PAGE).fillMaxWidth().height(room).verticalScroll(scroll)) {
                    Box(Modifier.fillMaxWidth().height(settings))
                }
            }
        }
        compose.waitForIdle()
        return scroll
    }

    /** What is drawn on the page's lower edge, on the screen's centre line, where the front crown stands. */
    private fun footPixel(): Color {
        compose.waitForIdle()
        val pixels = compose.onNodeWithTag(PAGE).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height - 2]
    }

    private fun px(dp: Dp) = (dp.value * compose.density.density).roundToInt()
}

/** The README's header as `scripts/design/readme-header.py` drew it: the disc, two crowns, the bird, a crown. */
private class HeaderSvg(mode: String) {
    private val svg = File("../docs/design/readme/header-$mode.svg").readText()
    val height = checkNotNull(VIEW_BOX.find(svg)).groupValues[1].toFloat()
    val circles = CIRCLE.findAll(svg).map { match ->
        val (x, y, radius, fill) = match.destructured
        Circle(x.toFloat(), y.toFloat(), radius.toFloat(), Color(0xFF000000 or fill.toLong(16)))
    }.toList()
    private val bird = checkNotNull(BIRD.find(svg)).groupValues.drop(1).map { it.toFloat() }
    val head = Offset(bird[0], bird[1])
    val iconUnit = bird[2]
    val iconHead = Offset(bird[3], bird[4])

    fun crowns() = TreeColors(circles[1].fill, circles[2].fill, circles[3].fill)
}

private class Circle(val x: Float, val y: Float, val radius: Float, val fill: Color) {
    fun from(disc: Circle) = TreeCircle(x - disc.x, y - disc.y, radius)
}

private val VIEW_BOX = Regex("""viewBox="0 0 [\d.]+ ([\d.]+)"""")
private val CIRCLE = Regex("""<circle cx="([\d.]+)" cy="([\d.]+)" r="([\d.]+)" fill="#([0-9A-F]{6})"/>""")
private val BIRD =
    Regex("""translate\(([\d.]+) ([\d.]+)\) scale\(-([\d.]+) [\d.]+\) translate\(-([\d.]+) -([\d.]+)\)""")
private const val PAGE = "page"
