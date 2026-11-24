package io.github.hazymorning.amsel.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.theme.Sizes
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun lengthText(seconds: Int): String {
    val (h, m) = lengthParts(seconds)
    return when {
        h == 0 -> stringResource(R.string.pages_length_minutes, m)
        m == 0 -> stringResource(R.string.pages_length_hours, h)
        else -> stringResource(R.string.pages_length_hours_minutes, h, m)
    }
}

@Composable
fun leftText(remainingSec: Int): String {
    val (h, m) = lengthParts(remainingSec)
    return when {
        h == 0 -> stringResource(R.string.spoken_left_minutes, m)
        m == 0 -> stringResource(R.string.spoken_left_hours, h)
        else -> stringResource(R.string.spoken_left_hours_minutes, h, m)
    }
}

@Composable
fun dateText(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(date, locale) {
        val skeleton = if (date.year == LocalDate.now().year) "MMMd" else "yMMMd"
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
    }
}

internal fun lengthParts(totalSec: Int): Pair<Int, Int> {
    val minutes = ((totalSec + SECONDS_PER_MINUTE / 2) / SECONDS_PER_MINUTE).coerceAtLeast(1)
    return minutes / MINUTES_PER_HOUR to minutes % MINUTES_PER_HOUR
}

private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60

private const val HEARD_ALPHA = 0.6f
private const val AHEAD_ALPHA = 0.12f

/** [fraction] is read while drawing, so progress redraws without recomposition. */
@Composable
fun ProgressLine(fraction: () -> Float, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().height(Sizes.ProgressLine)) {
        val line = Sizes.ProgressLine.toPx()
        val y = size.height / 2f
        // Inset by half the stroke, so the round caps stay inside the width.
        val start = line / 2f
        val end = size.width - line / 2f
        drawLine(ink.copy(alpha = AHEAD_ALPHA), Offset(start, y), Offset(end, y), line, StrokeCap.Round)
        val f = fraction().coerceIn(0f, 1f)
        if (f <= 0f) return@Canvas
        val along = start + f * (end - start)
        val rtl = layoutDirection == LayoutDirection.Rtl
        val from = if (rtl) size.width - start else start
        val to = if (rtl) size.width - along else along
        drawLine(ink.copy(alpha = HEARD_ALPHA), Offset(from, y), Offset(to, y), line, StrokeCap.Round)
    }
}
