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
import io.github.hazymorning.amsel.ui.components.textInk
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.LocalReducedMotion
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.isNight
import kotlin.math.roundToInt

/**
 * The README's picture at the foot of Settings: the icon's blackbird behind three crowns, before the
 * sun by day and the moon at night, cut by the edge it stands on. It is the header's drawing in one
 * scale ([ReadmeHeader], `scripts/design/readme-header.py`), so the two stay one picture. Each time it
 * comes into view over the dock, the sun grows into place, then the bird rises from behind the crowns;
 * with reduced motion it simply stands there. A picture and nothing more: it is not spoken, takes no
 * touch and keeps its size at any font scale.
 */
@Composable
internal fun SettingsBird(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val disc = if (colors.isNight) NightMoon else DaySun
    val crowns = if (colors.isNight) NightCrowns else DayCrowns
    val bird = painterResource(R.drawable.ic_launcher_foreground)
    val still = LocalReducedMotion.current
    val rise = remember { Rise() }
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
                // Over the dock's edge, not merely in the window: the dock covers the page's foot.
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
        // The painter draws from a bitmap of its own: on whole pixels it stays as sharp as the icon.
        val right = (centre.x + ReadmeHeader.Head.x * scale + ReadmeHeader.IconHead.x * unit).roundToInt().toFloat()
        val top = (centre.y + ReadmeHeader.Head.y * scale - ReadmeHeader.IconHead.y * unit).roundToInt().toFloat()
        // Below the edge the picture is cut by, the whole bird is out of sight.
        val below = size.height - (top + ReadmeHeader.ICON_CROWN * unit)
        clipRect {
            drawCircle(disc, ReadmeHeader.DISC * scale * rise.disc.value, centre)
            crown(ReadmeHeader.Back, crowns.back, centre, scale)
            crown(ReadmeHeader.Behind, crowns.behind, centre, scale)
            withTransform({
                translate(right, (top + below * (1f - rise.bird.value)).roundToInt().toFloat())
                scale(-1f, 1f, Offset.Zero)
            }) {
                with(bird) { draw(Size(ICON_SIDE * unit, ICON_SIDE * unit)) }
            }
            crown(ReadmeHeader.Front, crowns.front, centre, scale)
        }
    }
}

/** How far the sun has grown and the bird has risen, from 0 to 1, read only where they are drawn. */
private class Rise {
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

/** The share of the picture above the dock's edge that starts it. */
private const val IN_VIEW = 0.6f

private const val DISC_MS = 650
private const val BIRD_MS = 900

private fun DrawScope.crown(crown: Crown, tint: Color, disc: Offset, scale: Float) =
    drawCircle(tint, crown.radius * scale, disc + Offset(crown.x, crown.y) * scale)

/**
 * The settings and the picture under them. It stands on the page's end, which is the dock's edge once
 * the page is scrolled there; at the screen's foot where everything fits; and not at all where the
 * settings fit as they are and would scroll only for it ([footOf]).
 */
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
        val foot = footOf(
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

/** How tall the page is and where the picture's top stands on it; [bird] is null where it is left out. */
internal data class Foot(val height: Int, val bird: Int?)

/**
 * Where the picture goes on a page whose settings are [settings] high and end [end] over the dock, in a
 * window of [room]: [gap] under them where the page scrolls anyway, at the window's foot where all of
 * it fits, and nowhere where the settings fit and only the picture would make them scroll.
 */
internal fun footOf(settings: Int, bird: Int, gap: Int, end: Int, room: Int): Foot {
    val withBird = settings + gap + bird
    return when {
        settings + end > room -> Foot(withBird, settings + gap)
        withBird <= room -> Foot(room, room - bird)
        else -> Foot(room, null)
    }
}

/** The header's picture in its own measure, a card of 1200 by 450: what stands where from the disc's centre. */
internal object ReadmeHeader {
    const val DISC = 150f

    /** The card's lower edge, which cuts the crowns. */
    const val FOOT = 200f
    val Back = Crown(-164f, 290f, 150f)
    val Behind = Crown(161f, 255f, 175f)
    val Front = Crown(1f, 270f, 200f)

    /** The middle of the bird's head, and where the icon's 108 units have it; one unit's size. */
    val Head = Offset(6f, -12f)
    val IconHead = Offset(46.5f, 62f)
    const val ICON_UNIT = 4.4f

    /** Over the highest thing the icon draws (the sprig over the bird's head, at 42), in its units. */
    const val ICON_CROWN = 40f
}

internal data class Crown(val x: Float, val y: Float, val radius: Float)

internal data class CrownTints(val back: Color, val behind: Color, val front: Color)

internal val DayCrowns = CrownTints(Color(0xFFD3DAC4), Color(0xFFB8C5A2), Color(0xFFC7D1B5))
internal val NightCrowns = CrownTints(Color(0xFF2B3425), Color(0xFF34402C), Color(0xFF3B4832))

/** The paper raised by the step Surface 1 lies under it: the header's own sun is lost on the app's lighter page. */
private val DaySun = Color(0xFFFBFAF7)

/** A moon barely off the page, between its first and second surfaces: there, not shining, darker than the crowns. */
private val NightMoon = Color(0xFF21221E)

/** The sun: large enough that the bird's eye and beak read, small enough to stay a signature. */
private val Disc = 72.dp

internal val BirdHeight = Disc / 2 * ((ReadmeHeader.DISC + ReadmeHeader.FOOT) / ReadmeHeader.DISC)

/** From the licence line's baseline to the sun: one group with it, with room to breathe. */
private val BirdGap = Space.S12

private const val ICON_SIDE = 108f

/**
 * Amsel's name and whose Amsel is, centred under the last block, quiet: what Amsel is, not a
 * setting. The name is set as the README's picture sets it (Display's face, weight and tracking),
 * the same in every language; the licence line under it is smaller than any reading text.
 */
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
            modifier = Modifier.fillMaxWidth().textInk(type.displaySmall),
        )
        Spacer(Modifier.height(Space.S3))
        Words(
            note,
            small,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().textInk(small),
        )
    }
}

/** The licence line: a signature's small print. */
private val LicenceSize = 11.sp
private val LicenceLine = 14.sp

/** From the last block to the licence: the page's end stands apart from its settings. */
private val OverLicence = Space.S16
