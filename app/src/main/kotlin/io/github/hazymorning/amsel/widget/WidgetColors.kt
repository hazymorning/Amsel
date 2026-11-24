package io.github.hazymorning.amsel.widget

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import io.github.hazymorning.amsel.R

/** Cover: the cover blurred; Darkened: the cover sharp under a dark veil; Plain: a flat background; Clear: none. */
enum class WidgetStyle(val id: String) {
    Cover("cover"),
    Darkened("darkened"),
    Plain("plain"),
    Clear("clear"),
    ;

    val covered: Boolean get() = this == Cover || this == Darkened

    companion object {
        fun of(id: String?): WidgetStyle = entries.firstOrNull { it.id == id } ?: Cover
    }
}

/** By resource, not ARGB, so the launcher resolves day and night itself. */
internal enum class WidgetColors(
    @ColorRes val words: Int,
    @ColorRes val quiet: Int,
    @ColorRes val glyph: Int,
    /** The glyph on the filled play cell. */
    @ColorRes val onDisc: Int,
    @DrawableRes val cell: Int,
    @DrawableRes val playCell: Int,
    /** The cell's colour, which also tints an empty cover. */
    @ColorRes val cellColor: Int,
    /** Transparent but for the cell's shade while pressed. */
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

internal class WidgetBackground(
    val tone: WidgetColors,
    @DrawableRes val background: Int,
    val cover: WidgetCover?,
    @DrawableRes val veil: Int = R.drawable.widget_veil_light,
) {
    companion object {
        /** [clearInk] (dark text on the wallpaper) applies to [WidgetStyle.Clear] only. */
        fun of(style: WidgetStyle, cover: WidgetCover?, clearInk: Boolean): WidgetBackground = when (style) {
            WidgetStyle.Cover -> if (cover == null || cover.light) {
                WidgetBackground(WidgetColors.Light, R.drawable.widget_background_cover, cover)
            } else {
                WidgetBackground(
                    WidgetColors.Dark,
                    R.drawable.widget_background_cover,
                    cover,
                    R.drawable.widget_veil_dark,
                )
            }

            WidgetStyle.Darkened -> WidgetBackground(
                if (cover == null) WidgetColors.Light else WidgetColors.Dark,
                R.drawable.widget_background_cover,
                cover,
                R.drawable.widget_veil_darkened,
            )

            WidgetStyle.Plain -> WidgetBackground(WidgetColors.Plain, R.drawable.widget_background_plain, null)

            WidgetStyle.Clear -> WidgetBackground(
                if (clearInk) WidgetColors.ClearInk else WidgetColors.ClearPaper,
                R.drawable.widget_background_clear,
                null,
            )
        }
    }
}

internal fun wallpaperWantsDarkText(context: Context): Boolean {
    val colors = wallpaperColors(context) ?: return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
    }
    return ColorUtils.calculateLuminance(colors.primaryColor.toArgb()) > LIGHT_WALLPAPER
}

internal fun wallpaperColor(context: Context): Int? = wallpaperColors(context)?.primaryColor?.toArgb()

private fun wallpaperColors(context: Context): WallpaperColors? = try {
    WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
} catch (e: RuntimeException) {
    // No wallpaper service, or it refuses: light text, which suits most wallpapers.
    null
}

private const val LIGHT_WALLPAPER = 0.5

internal class WallpaperColorsListener(private val context: Context, changed: () -> Unit) {
    private val listener = WallpaperManager.OnColorsChangedListener { _, which ->
        if (which and WallpaperManager.FLAG_SYSTEM != 0) changed()
    }

    fun listen(on: Boolean) {
        val wallpapers = WallpaperManager.getInstance(context)
        try {
            if (on) {
                wallpapers.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
            } else {
                wallpapers.removeOnColorsChangedListener(listener)
            }
        } catch (e: RuntimeException) {
            // No wallpaper service: a Clear widget picks up wallpaper changes at its next draw.
        }
    }
}
