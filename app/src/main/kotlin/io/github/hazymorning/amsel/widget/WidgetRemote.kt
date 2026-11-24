package io.github.hazymorning.amsel.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.core.graphics.ColorUtils
import io.github.hazymorning.amsel.MainActivity
import io.github.hazymorning.amsel.ui.navigation.DeepLink

internal fun open(context: Context, opening: DeepLink?): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (opening != null) intent.setAction(Intent.ACTION_VIEW).setData(opening.link)
    return PendingIntent.getActivity(
        context,
        opening?.ordinal ?: -1,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

internal fun RemoteViews.shows(id: Int, shown: Boolean) = setViewVisibility(id, if (shown) View.VISIBLE else View.GONE)

/** By resource from Android 12, so the launcher resolves day and night itself; before, the current colour. */
internal fun RemoteViews.textColor(context: Context, id: Int, @ColorRes color: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setColorStateList(id, "setTextColor", color)
    } else {
        setTextColor(id, context.getColor(color))
    }
}

/**
 * Before Android 12 the colour filter (SRC_ATOP) would let the white glyph show through a translucent
 * colour, so the colour goes on opaque and its alpha as the image's alpha.
 */
internal fun RemoteViews.glyphColor(context: Context, id: Int, @ColorRes color: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setColorStateList(id, "setImageTintList", color)
    } else {
        val argb = context.getColor(color)
        setInt(id, "setColorFilter", argb or OPAQUE)
        setInt(id, "setImageAlpha", argb ushr ALPHA_SHIFT)
    }
}

internal fun RemoteViews.glyphArgb(id: Int, argb: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setColorStateList(id, "setImageTintList", ColorStateList.valueOf(argb))
    } else {
        setInt(id, "setColorFilter", argb or OPAQUE)
        setInt(id, "setImageAlpha", argb ushr ALPHA_SHIFT)
    }
}

/**
 * Null clears the tint, which a launcher would otherwise keep on a reused view. A no-op before Android 12,
 * which lacks RemoteViews.setColorStateList.
 */
internal fun RemoteViews.cellArgb(id: Int, argb: Int?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val states = argb?.let {
        val pressed = ColorUtils.blendARGB(it, OPAQUE, PRESSED_DARKEN)
        ColorStateList(arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()), intArrayOf(pressed, it))
    }
    setColorStateList(id, "setBackgroundTintList", states)
}

private const val PRESSED_DARKEN = 0.18f
private const val OPAQUE = 0xFF000000.toInt()
private const val ALPHA_SHIFT = 24
