package io.github.hazymorning.amsel.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

// Every Material slot is set from the palette, so the Material components still in use agree with it.
// PaletteContrastTest checks the contrast pairs.

/** Dims a played song's cover: it recedes without going dark. */
const val DIM_ALPHA = 0.32f

const val HAIRLINE_ALPHA = 0.08f

/** Translucent rather than a solid tone, so a quiet pill looks the same on the dock as on a page. */
const val PILL_ALPHA = 0.055f

const val DISABLED_ALPHA = 0.38f

internal object Night {
    val Background = Color(0xFF131412)
    val Surface1 = Color(0xFF1B1C19)
    val Surface2 = Color(0xFF252621)
    val Ink = Color(0xFFE6E5DE)
    val Secondary = Color(0xFF9A998F)
    val Faint = Color(0xFF67675F)
    val Moss = Color(0xFFA3B486)
    val Error = Color(0xFFD99A8E)
}

internal object Day {
    val Background = Color(0xFFF2F1EC)
    val Surface1 = Color(0xFFE9E8E1)
    val Surface2 = Color(0xFFDFDED6)
    val Ink = Color(0xFF191A17)
    val Secondary = Color(0xFF66665E)
    val Faint = Color(0xFF87877E)
    val Moss = Color(0xFF4F6A3B)
    val Error = Color(0xFF9E3D30)
}

private val Scrim = Color(0xFF0B0C0A)

internal val LightColors = lightColorScheme(
    primary = Day.Ink,
    onPrimary = Day.Background,
    primaryContainer = Day.Surface2,
    onPrimaryContainer = Day.Ink,
    inversePrimary = Night.Ink,
    secondary = Day.Secondary,
    onSecondary = Day.Background,
    secondaryContainer = Day.Surface2,
    onSecondaryContainer = Day.Ink,
    tertiary = Day.Moss,
    onTertiary = Day.Background,
    tertiaryContainer = Day.Surface2,
    onTertiaryContainer = Day.Ink,
    background = Day.Background,
    onBackground = Day.Ink,
    surface = Day.Background,
    onSurface = Day.Ink,
    surfaceVariant = Day.Surface1,
    onSurfaceVariant = Day.Secondary,
    surfaceTint = Color.Transparent,
    inverseSurface = Day.Ink,
    inverseOnSurface = Day.Background,
    error = Day.Error,
    onError = Day.Background,
    errorContainer = Day.Surface2,
    onErrorContainer = Day.Error,
    outline = Day.Faint,
    outlineVariant = Day.Ink.copy(alpha = HAIRLINE_ALPHA).compositeOver(Day.Background),
    scrim = Scrim,
    surfaceBright = Day.Background,
    surfaceContainer = Day.Surface1,
    surfaceContainerHigh = Day.Surface2,
    surfaceContainerHighest = Day.Surface2,
    surfaceContainerLow = Day.Surface1,
    surfaceContainerLowest = Day.Background,
    surfaceDim = Day.Surface1,
)

internal val DarkColors = darkColorScheme(
    primary = Night.Ink,
    onPrimary = Night.Background,
    primaryContainer = Night.Surface2,
    onPrimaryContainer = Night.Ink,
    inversePrimary = Day.Ink,
    secondary = Night.Secondary,
    onSecondary = Night.Background,
    secondaryContainer = Night.Surface2,
    onSecondaryContainer = Night.Ink,
    tertiary = Night.Moss,
    onTertiary = Night.Background,
    tertiaryContainer = Night.Surface2,
    onTertiaryContainer = Night.Ink,
    background = Night.Background,
    onBackground = Night.Ink,
    surface = Night.Background,
    onSurface = Night.Ink,
    surfaceVariant = Night.Surface1,
    onSurfaceVariant = Night.Secondary,
    surfaceTint = Color.Transparent,
    inverseSurface = Night.Ink,
    inverseOnSurface = Night.Background,
    error = Night.Error,
    onError = Night.Background,
    errorContainer = Night.Surface2,
    onErrorContainer = Night.Error,
    outline = Night.Faint,
    outlineVariant = Night.Ink.copy(alpha = HAIRLINE_ALPHA).compositeOver(Night.Background),
    scrim = Scrim,
    surfaceBright = Night.Surface2,
    surfaceContainer = Night.Surface1,
    surfaceContainerHigh = Night.Surface2,
    surfaceContainerHighest = Night.Surface2,
    surfaceContainerLow = Night.Surface1,
    surfaceContainerLowest = Night.Background,
    surfaceDim = Night.Background,
)

val ColorScheme.isNight: Boolean get() = surface == Night.Background

val ColorScheme.controls: Color get() = if (isNight) onSurface else onSurface.copy(alpha = CONTROL_ALPHA)

private const val CONTROL_ALPHA = 0.87f
