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

/**
 * A widget's page: the launcher opens it to change the widget, and before Android 12 also as it is
 * placed. Leaving without Done leaves the widget as it was, or places none.
 */
class WidgetSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, answer(widgetId))
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        // Exported for launchers that open it themselves: only Amsel's own widgets are set here.
        if (provider?.packageName != packageName) {
            finish()
            return
        }
        val graph = graph
        // The new widget shows what plays at once, the picture on this page too.
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
