package io.github.hazymorning.amsel.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.hazymorning.amsel.R

private val W440 = FontWeight(440)
private val W500 = FontWeight(500)
private val W400 = FontWeight(400)
private val W520 = FontWeight(520)
private val W560 = FontWeight(560)
private val W600 = FontWeight(600)
private val W650 = FontWeight(650)
private val W700 = FontWeight(700)

private class FontFamilies(val title: FontFamily, val text: FontFamily)

private val Commissioner = FontFamilies(
    title = FontFamily(Font(R.font.commissioner_title_650, W650), Font(R.font.commissioner_title_700, W700)),
    text = FontFamily(
        Font(R.font.commissioner_400, W400),
        Font(R.font.commissioner_440, W440),
        Font(R.font.commissioner_500, W500),
        Font(R.font.commissioner_520, W520),
        Font(R.font.commissioner_560, W560),
        Font(R.font.commissioner_600, W600),
        Font(R.font.commissioner_700, W700),
    ),
)

/** Cap height as a share of the font size, from each font's OS/2 table. */
private val CapHeights: Map<FontFamily, Float> = mapOf(
    Commissioner.title to 0.713f,
    Commissioner.text to 0.713f,
)

private const val CAP_HEIGHT = 0.7f

/**
 * Under nonlinear font scaling Compose keeps a line in proportion to its scaled size, which
 * `lineHeight.toDp()` does not (at 200%, 20 sp lines of 13 sp text lay out at about 37 dp, not 34).
 */
fun Density.lineHeight(style: TextStyle): Dp = style.fontSize.toDp() * (style.lineHeight.value / style.fontSize.value)

fun Density.secondLine(style: TextStyle): Dp = lineHeight(style) + Space.Line

fun Density.capHeight(style: TextStyle): Dp = style.fontSize.toDp() * (CapHeights[style.fontFamily] ?: CAP_HEIGHT)

private val Centred = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(family: FontFamily, weight: FontWeight, size: Float, line: Int, tracking: Float = 0f) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = (tracking / size).em,
    fontSynthesis = FontSynthesis.None,
    lineHeightStyle = Centred,
    lineBreak = LineBreak.Heading,
)

/**
 * One Material name per role: displaySmall screen titles, headlineSmall Now Playing's title, titleLarge
 * headings, titleMedium a slim bar's title, titleSmall captions and chips, bodyLarge list titles, buttons
 * and sentences, bodyMedium second lines, labelLarge the mini player's title, labelMedium times.
 */
private fun typography(faces: FontFamilies): Typography {
    // Tracking in sp, as docs/DESIGN.md gives it.
    val display = style(faces.title, W650, 30f, 36, -0.45f)
    val title = style(faces.title, W700, 26f, 32, -0.26f)
    val heading = style(faces.title, W700, 20.5f, 28, -0.3f)
    val caption = style(faces.text, W600, 14f, 20, -0.2f)
    val body = style(faces.text, W440, 16f, 22)
    val meta = style(faces.text, W400, 12f, 16, 0.25f)
    return Typography(
        displayLarge = display,
        displayMedium = display,
        displaySmall = display,
        headlineLarge = display,
        headlineMedium = display,
        headlineSmall = title,
        titleLarge = heading,
        titleMedium = body.copy(fontWeight = W560),
        titleSmall = caption,
        bodyLarge = body,
        bodyMedium = meta,
        bodySmall = meta,
        labelLarge = caption,
        labelMedium = style(faces.text, W500, 12f, 16).tabular(),
        labelSmall = style(faces.text, W560, 12f, 14),
    )
}

private val Amsel by lazy { typography(Commissioner) }

internal fun amselTypography(): Typography = Amsel

val Typography.dockLine: TextStyle get() = bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp)

fun TextStyle.firm(): TextStyle = copy(fontWeight = W520)

/** Equal-width figures, so "0:09" turning to "0:10" does not shift; tracking stays off so "1:11" holds. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum", letterSpacing = 0.sp)

/** Sized in dp like the [glyph] they sit in, so they ignore the font scale and grow with the glyph. */
fun Density.glyphFigures(times: TextStyle, glyph: Dp = 24.dp): TextStyle {
    val size = (GLYPH_FIGURES.dp * (glyph / 24.dp)).toSp()
    return times.copy(fontWeight = W560, fontSize = size, lineHeight = size).tabular()
}

private const val GLYPH_FIGURES = 9f
