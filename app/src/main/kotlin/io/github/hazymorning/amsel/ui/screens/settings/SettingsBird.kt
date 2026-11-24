package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.LocalDockInset
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.capToBaseline
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.LocalReducedMotion
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.isNight
import kotlin.math.roundToInt

/**
 * The README header's drawing at one scale ([ReadmeHeader], `scripts/design/readme-header.py`); the two
 * stay in step. Decorative only: not spoken, no touch, the same size at any font scale.
 */
@Composable
internal fun SettingsBird(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val disc = if (colors.isNight) NightMoon else DaySun
    val trees = if (colors.isNight) NightTrees else DayTrees
    val bird = painterResource(R.drawable.ic_launcher_foreground)
    val still = LocalReducedMotion.current
    val rise = remember { HeaderAnimation() }
    var inView by remember { mutableStateOf(false) }
    LaunchedEffect(inView, still) {
        when {
            still -> rise.show()
            inView -> rise.play()
            else -> rise.hide()
        }
    }
    val window = LocalWindowInfo.current
    val dock by LocalDockInset.current
    val dockPx = with(LocalDensity.current) { dock.toPx() }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(BirdHeight)
            .onGloballyPositioned { at ->
                // Above the dock's edge, not merely in the window: the dock covers the page's foot.
                val top = at.positionInWindow().y
                val edge = window.containerSize.height - dockPx
                if (top + at.size.height * IN_VIEW <= edge) {
                    inView = true
                } else if (top >= edge) {
                    inView = false
                }
            },
    ) {
        val scale = Disc.toPx() / 2 / ReadmeHeader.DISC
        val centre = Offset(size.width / 2, ReadmeHeader.DISC * scale)
        val unit = ReadmeHeader.ICON_UNIT * scale
        // The painter renders through a bitmap of its own, so whole-pixel offsets keep it as sharp as the icon.
        val right = (centre.x + ReadmeHeader.Head.x * scale + ReadmeHeader.IconHead.x * unit).roundToInt().toFloat()
        val top = (centre.y + ReadmeHeader.Head.y * scale - ReadmeHeader.IconHead.y * unit).roundToInt().toFloat()
        // The drop that puts the whole bird below the clipped bottom edge.
        val below = size.height - (top + ReadmeHeader.ICON_CROWN * unit)
        clipRect {
            drawCircle(disc, ReadmeHeader.DISC * scale * rise.disc.value, centre)
            drawTree(ReadmeHeader.Back, trees.back, centre, scale)
            drawTree(ReadmeHeader.Behind, trees.behind, centre, scale)
            withTransform({
                translate(right, (top + below * (1f - rise.bird.value)).roundToInt().toFloat())
                scale(-1f, 1f, Offset.Zero)
            }) {
                with(bird) { draw(Size(ICON_SIDE * unit, ICON_SIDE * unit)) }
            }
            drawTree(ReadmeHeader.Front, trees.front, centre, scale)
        }
    }
}

/** 0 to 1, read only in the draw phase. */
private class HeaderAnimation {
    val disc = Animatable(0f)
    val bird = Animatable(0f)

    suspend fun play() {
        disc.animateTo(1f, tween(DISC_MS, easing = Motion.Settle))
        bird.animateTo(1f, tween(BIRD_MS, easing = Motion.Settle))
    }

    suspend fun hide() {
        disc.snapTo(0f)
        bird.snapTo(0f)
    }

    suspend fun show() {
        disc.snapTo(1f)
        bird.snapTo(1f)
    }
}

private const val IN_VIEW = 0.6f

private const val DISC_MS = 650
private const val BIRD_MS = 900

private fun DrawScope.drawTree(crown: TreeCircle, tint: Color, disc: Offset, scale: Float) =
    drawCircle(tint, crown.radius * scale, disc + Offset(crown.x, crown.y) * scale)

@Composable
internal fun PageWithBird(modifier: Modifier = Modifier, settings: @Composable ColumnScope.() -> Unit) {
    Layout(
        content = {
            Column(content = settings)
            SettingsBird()
        },
        modifier = modifier,
    ) { (page, picture), constraints ->
        val loose = constraints.copy(minHeight = 0)
        val content = page.measure(loose)
        val bird = picture.measure(loose)
        val foot = picturePlacement(
            settings = content.height,
            bird = bird.height,
            gap = BirdGap.roundToPx(),
            end = Space.ListEnd.roundToPx(),
            room = constraints.minHeight,
        )
        layout(constraints.maxWidth, foot.height) {
            content.place(0, 0)
            if (foot.bird != null) bird.place(0, foot.bird)
        }
    }
}

/** [bird] is the picture's top, null where the picture is left out. */
internal data class PicturePlacement(val height: Int, val bird: Int?)

/** The picture is left out where only it would make the page scroll. */
internal fun picturePlacement(settings: Int, bird: Int, gap: Int, end: Int, room: Int): PicturePlacement {
    val withBird = settings + gap + bird
    return when {
        settings + end > room -> PicturePlacement(withBird, settings + gap)
        withBird <= room -> PicturePlacement(room, room - bird)
        else -> PicturePlacement(room, null)
    }
}

/** In the header's units (a 1200 by 450 card), measured from the disc's centre. */
internal object ReadmeHeader {
    const val DISC = 150f

    /** The card's lower edge, which cuts the crowns. */
    const val FOOT = 200f
    val Back = TreeCircle(-164f, 290f, 150f)
    val Behind = TreeCircle(161f, 255f, 175f)
    val Front = TreeCircle(1f, 270f, 200f)

    /** The middle of the bird's head, the same point in the icon's 108 units, and one icon unit's size. */
    val Head = Offset(6f, -12f)
    val IconHead = Offset(46.5f, 62f)
    const val ICON_UNIT = 4.4f

    /** Just above the icon's highest drawing (the sprig over the bird's head, at 42), in icon units. */
    const val ICON_CROWN = 40f
}

internal data class TreeCircle(val x: Float, val y: Float, val radius: Float)

internal data class TreeColors(val back: Color, val behind: Color, val front: Color)

internal val DayTrees = TreeColors(Color(0xFFD3DAC4), Color(0xFFB8C5A2), Color(0xFFC7D1B5))
internal val NightTrees = TreeColors(Color(0xFF2B3425), Color(0xFF34402C), Color(0xFF3B4832))

/**
 * The page tone lightened by the step that Surface 1 lies below it: the header's own sun is lost on the
 * app's lighter page.
 */
private val DaySun = Color(0xFFFBFAF7)

/** Between the night page's first and second surface tones and darker than the crowns, so the moon stays faint. */
private val NightMoon = Color(0xFF21221E)

/** Large enough for the bird's eye and beak to read, small enough to stay a signature. */
private val Disc = 72.dp

internal val BirdHeight = Disc / 2 * ((ReadmeHeader.DISC + ReadmeHeader.FOOT) / ReadmeHeader.DISC)

/** From the licence line's baseline to the sun. */
private val BirdGap = Space.S12

private const val ICON_SIDE = 108f

/** The name is set as the README header sets it (Display's face, weight and tracking). */
@Composable
internal fun LicenceNote() {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val note = stringResource(R.string.settings_rights)
    val small = type.bodyMedium.copy(fontSize = LicenceSize, lineHeight = LicenceLine)
    Column(Modifier.fillMaxWidth().padding(top = OverLicence, start = Keyline.Margin, end = Keyline.Margin)) {
        Words(
            stringResource(R.string.app_name),
            type.displaySmall,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().capToBaseline(type.displaySmall),
        )
        Spacer(Modifier.height(Space.S3))
        Words(
            note,
            small,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().capToBaseline(small),
        )
    }
}

/** Sized so the licence line is about as wide as the name above it. */
private val LicenceSize = 8.5.sp
private val LicenceLine = 11.sp

private val OverLicence = Space.S16
