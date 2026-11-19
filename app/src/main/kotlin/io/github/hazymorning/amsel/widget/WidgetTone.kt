package io.github.hazymorning.amsel.widget

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import io.github.hazymorning.amsel.R

/**
 * How a widget dresses: over the cover of what plays, blurred or sharp and darkened; plain in the
 * system's colours; or clear on the wallpaper.
 */
enum class WidgetStyle(val id: String) {
    Cover("cover"),
    Darkened("darkened"),
    Plain("plain"),
    Clear("clear"),
    ;

    /** Over the cover of what plays, which gives play and pause its colour. */
    val covered: Boolean get() = this == Cover || this == Darkened

    companion object {
        fun of(id: String?): WidgetStyle = entries.firstOrNull { it.id == id } ?: Cover
    }
}

/**
 * The colours of a widget's words and buttons, by resource, so the launcher switches day and night
 * itself: over a light cover or none, over a dark cover, plain, and clear in ink or in paper.
 */
internal enum class Tone(
    @ColorRes val words: Int,
    @ColorRes val quiet: Int,
    @ColorRes val glyph: Int,
    /** The glyph on play and pause's filled cell. */
    @ColorRes val onDisc: Int,
    /** A button's cell, and play and pause's, filled; and the cell's colour for an empty cover. */
    @DrawableRes val cell: Int,
    @DrawableRes val playCell: Int,
    @ColorRes val cellColor: Int,
    /** A button without a cell, shaded while pressed. */
    @DrawableRes val bare: Int,
) {
    Light(
        R.color.widget_words_light,
        R.color.widget_quiet_light,
        R.color.widget_glyph_light,
        R.color.widget_on_disc_light,
        R.drawable.widget_cell_light,
        R.drawable.widget_cell_play_light,
        R.color.widget_cell_light,
        R.drawable.widget_bare_light,
    ),
    Dark(
        R.color.widget_words_dark,
        R.color.widget_quiet_dark,
        R.color.widget_glyph_dark,
        R.color.widget_on_disc_dark,
        R.drawable.widget_cell_dark,
        R.drawable.widget_cell_play_dark,
        R.color.widget_cell_dark,
        R.drawable.widget_bare_dark,
    ),
    Plain(
        R.color.widget_words_plain,
        R.color.widget_quiet_plain,
        R.color.widget_glyph_plain,
        R.color.widget_on_disc_plain,
        R.drawable.widget_cell_plain,
        R.drawable.widget_cell_play_plain,
        R.color.widget_cell_plain,
        R.drawable.widget_bare_plain,
    ),
    ClearInk(
        R.color.widget_words_ink,
        R.color.widget_quiet_ink,
        R.color.widget_glyph_ink,
        R.color.widget_on_disc_ink,
        R.drawable.widget_cell_ink,
        R.drawable.widget_cell_play_ink,
        R.color.widget_cell_ink,
        R.drawable.widget_bare_ink,
    ),
    ClearPaper(
        R.color.widget_words_paper,
        R.color.widget_quiet_paper,
        R.color.widget_glyph_paper,
        R.color.widget_on_disc_paper,
        R.drawable.widget_cell_paper,
        R.drawable.widget_cell_play_paper,
        R.color.widget_cell_paper,
        R.drawable.widget_bare_paper,
    ),
}

/** What lies under a widget's words: its ground, and the cover under a [veil] where it shows one. */
internal class WidgetGround(
    val tone: Tone,
    @DrawableRes val background: Int,
    val cover: WidgetCover?,
    @DrawableRes val veil: Int = R.drawable.widget_veil_light,
) {
    companion object {
        /**
         * [style]'s ground with [cover] (under a cover's looks), on a wallpaper that wants ink
         * ([clearInk], Clear only). Darkened, the words are paper whatever the cover, as on a dark one.
         */
        fun of(style: WidgetStyle, cover: WidgetCover?, clearInk: Boolean): WidgetGround = when (style) {
            WidgetStyle.Cover -> if (cover == null || cover.light) {
                WidgetGround(Tone.Light, R.drawable.widget_background_cover, cover)
            } else {
                WidgetGround(Tone.Dark, R.drawable.widget_background_cover, cover, R.drawable.widget_veil_dark)
            }

            WidgetStyle.Darkened -> WidgetGround(
                if (cover == null) Tone.Light else Tone.Dark,
                R.drawable.widget_background_cover,
                cover,
                R.drawable.widget_veil_darkened,
            )

            WidgetStyle.Plain -> WidgetGround(Tone.Plain, R.drawable.widget_background_plain, null)

            WidgetStyle.Clear -> WidgetGround(
                if (clearInk) Tone.ClearInk else Tone.ClearPaper,
                R.drawable.widget_background_clear,
                null,
            )
        }
    }
}

/**
 * Whether the wallpaper wants dark words, as Android tells its own launcher: a clear widget's words
 * take ink there and paper elsewhere. Before Android 12, by how light the wallpaper's main colour is.
 */
internal fun wallpaperWantsInk(context: Context): Boolean {
    val colors = wallpaperColors(context) ?: return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
    }
    return ColorUtils.calculateLuminance(colors.primaryColor.toArgb()) > LIGHT_WALLPAPER
}

/** The wallpaper's main colour, for a widget's page to stand the widget on; null where Android does not say. */
internal fun wallpaperColor(context: Context): Int? = wallpaperColors(context)?.primaryColor?.toArgb()

private fun wallpaperColors(context: Context): WallpaperColors? = try {
    WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
} catch (e: RuntimeException) {
    // A phone without a wallpaper service, or one that refuses: paper, as over most pictures.
    null
}

/** Above this the words read better in ink, as Android's own hint judges a wallpaper. */
private const val LIGHT_WALLPAPER = 0.5
