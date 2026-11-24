package io.github.hazymorning.amsel.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color.TRANSPARENT
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.ThemeMode

/** Before Android 12 it also runs on placement, where RESULT_CANCELED places no widget. */
class WidgetSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, answer(widgetId))
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        // Exported for launchers, so any app can start it: only Amsel's own widget ids are accepted.
        if (provider?.packageName != packageName) {
            finish()
            return
        }
        val graph = graph
        // So the new widget and this page's preview show current content at once.
        graph.widgets.follow()
        val wallpaper = wallpaperColor(this)?.let(::Color)
        setContent {
            val prefs by graph.data.prefs.prefs.collectAsStateWithLifecycle()
            AmselTheme(ThemeMode.of(prefs?.theme)) {
                WidgetSetupRoute(
                    widgetId,
                    wallpaper,
                    onDone = {
                        setResult(RESULT_OK, answer(widgetId))
                        finish()
                    },
                    onBack = ::finish,
                )
            }
        }
    }

    private fun answer(widgetId: Int) = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
}
