package io.github.hazymorning.amsel.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/** [pref] is the value stored in `Prefs.theme`. */
enum class ThemeMode(val pref: String) {
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {
        fun of(pref: String?): ThemeMode = entries.firstOrNull { it.pref == pref } ?: System
    }
}

/**
 * From Android 12, so the splash and what the system draws for the app, such as the text toolbar, follow
 * the app's choice. A change recreates the activity.
 */
fun Context.applyNightMode(mode: ThemeMode) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    getSystemService(UiModeManager::class.java).setApplicationNightMode(
        when (mode) {
            ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
        },
    )
}

@Composable
fun AmselTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val scheme = if (dark) DarkColors else LightColors
    MaterialTheme(
        colorScheme = scheme,
        typography = amselTypography(),
    ) {
        val press = remember(scheme) { PressIndication(scheme.onSurface, Shapes.Tile) }
        val selection = remember(scheme) {
            TextSelectionColors(handleColor = scheme.onSurface, backgroundColor = scheme.onSurface.copy(alpha = 0.2f))
        }
        CompositionLocalProvider(
            LocalIndication provides press,
            LocalContentColor provides scheme.onSurface,
            LocalTextSelectionColors provides selection,
            LocalReducedMotion provides rememberReducedMotion(),
            content = content,
        )
    }
}
