package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.capHeight
import io.github.hazymorning.amsel.ui.theme.lineHeight
import io.github.hazymorning.amsel.ui.theme.secondLine
import kotlin.math.roundToInt

/** From a mark's edge (the play bars, a download's) to the words after it: a space and a little more. */
private val MarkGap = 6.dp

/**
 * The title, and the second line with its baseline one line under the title's. The layout is the
 * ink (cap top to last baseline) plus what the line boxes need around it, or the ink alone
 * [besideCover], where the cover sets the row's height; either way the ink is centred. [ring] puts
 * the playing ring before the second line, or before the title when there is none.
 */
@Composable
internal fun RowText(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    twoLines: Boolean = subtitle != null,
    playing: Boolean = false,
    ring: Boolean = false,
    mark: (@Composable () -> Unit)? = null,
    quiet: Boolean = false,
    besideCover: Boolean = false,
    titleLines: Int = 1,
    subtitleLines: Int = 1,
) {
    val titleStyle = MaterialTheme.typography.bodyLarge
    val nowPlaying = stringResource(R.string.player_now_playing)
    val colors = MaterialTheme.colorScheme
    val secondStyle = MaterialTheme.typography.bodyMedium
    val second = subtitle?.takeIf { it.isNotBlank() }
    RowLines(
        titleStyle,
        besideCover,
        modifier.then(if (playing) Modifier.semantics { stateDescription = nowPlaying } else Modifier),
    ) {
        val titleColor = if (quiet) colors.onSurfaceVariant else colors.onSurface
        val titleLine: @Composable () -> Unit = {
            Words(
                title,
                titleStyle,
                color = titleColor,
                maxLines = titleLines,
                tail = second.takeIf { !twoLines },
                modifier = Modifier.inkStart(title, titleStyle),
            )
        }
        // The play bars stand where a download's mark would: what plays matters more.
        val before: (@Composable () -> Unit)? = if (ring) {
            { PlayBars() }
        } else {
            mark
        }
        if (before != null && (second == null || !twoLines)) MarkBefore(titleStyle, before, titleLine) else titleLine()
        if (twoLines && second != null) {
            val line: @Composable () -> Unit = {
                Words(
                    second,
                    secondStyle,
                    color = colors.onSurfaceVariant,
                    maxLines = subtitleLines,
                    modifier = Modifier.inkStart(second, secondStyle),
                )
            }
            if (before != null) MarkBefore(secondStyle, before, line) else line()
        }
    }
}

/** The play bars before the line ([MarkBefore]). */
@Composable
internal fun BarsBefore(
    style: TextStyle,
    line: @Composable () -> Unit,
    moving: Boolean = LocalMusicPlays.current.value,
) = MarkBefore(style, { PlayBars(moving = moving) }, line)

/** [mark] centred on [style]'s capitals, then the line; the line's baselines are the block's. */
@Composable
internal fun MarkBefore(style: TextStyle, mark: @Composable () -> Unit, line: @Composable () -> Unit) {
    val cap = with(LocalDensity.current) { capHeight(style).toPx() }
    Layout(content = {
        mark()
        line()
    }) { measurables, constraints ->
        val ring = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gap = MarkGap.roundToPx()
        val text = measurables[1].measure(
            constraints.copy(minWidth = 0, maxWidth = (constraints.maxWidth - ring.width - gap).coerceAtLeast(0)),
        )
        val baseline = text[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: text.height
        val last = text[LastBaseline].takeIf { it != AlignmentLine.Unspecified } ?: baseline
        val ringTop = (baseline - cap / 2f - ring.height / 2f).roundToInt()
        val top = maxOf(0, -ringTop)
        val height = maxOf(text.height + top, ringTop + top + ring.height)
        layout(
            maxOf(ring.width + gap + text.width, constraints.minWidth),
            height,
            mapOf(FirstBaseline to baseline + top, LastBaseline to last + top),
        ) {
            ring.placeRelative(0, ringTop + top)
            text.placeRelative(ring.width + gap, top)
        }
    }
}

/**
 * Lays out [RowText]'s lines, and the skeleton bars that stand in for them, alike. [inset] keeps
 * room over and under them; the dock's larger lines take less, so they stay within its cover's height.
 * [baselines] sets how far the second line's baseline stands under the title's, where a list's
 * rhythm would leave a small card's two lines too far apart.
 */
@Composable
internal fun RowLines(
    titleStyle: TextStyle,
    besideCover: Boolean,
    modifier: Modifier = Modifier,
    secondStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    inset: Dp = Space.S2,
    baselines: Dp? = null,
    lines: @Composable () -> Unit,
) {
    Layout(lines, modifier.padding(vertical = inset)) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0)
        val title = measurables[0].measure(loose)
        val second = measurables.getOrNull(1)?.measure(loose)
        val titleLine = lineHeight(titleStyle).toPx()
        val titleCap = capHeight(titleStyle).toPx()
        val firstBaseline = title.line(FirstBaseline, (titleLine + titleCap) / 2)
        val titleBaseline = title.line(LastBaseline, title.height - (titleLine - titleCap) / 2)
        val capTop = firstBaseline - titleCap.roundToInt()
        val secondHeight = lineHeight(secondStyle).toPx()
        val secondBaseline = titleBaseline + (baselines ?: secondLine(secondStyle)).roundToPx()
        // A second text that wraps (a setting's description) adds its further lines under its first.
        val secondSpan = second?.let { it.line(LastBaseline, 0f) - it.line(FirstBaseline, 0f) } ?: 0
        val lastBaseline = if (second == null) titleBaseline else secondBaseline + secondSpan
        val below = if (second == null) {
            title.height - titleBaseline
        } else {
            second.height - second.line(LastBaseline, (secondHeight + capHeight(secondStyle).toPx()) / 2)
        }
        val pad = if (besideCover) 0 else maxOf(capTop, below)
        val height = maxOf(lastBaseline - capTop + pad * 2, constraints.minHeight)
        val top = (height - (lastBaseline - capTop)) / 2 - capTop
        layout(maxOf(title.width, second?.width ?: 0, constraints.minWidth), height) {
            title.placeRelative(0, top)
            second?.placeRelative(0, top + secondBaseline - second.line(FirstBaseline, 0f))
        }
    }
}

private fun Placeable.line(line: AlignmentLine, fallback: Float): Int =
    get(line).takeIf { it != AlignmentLine.Unspecified } ?: fallback.roundToInt()
