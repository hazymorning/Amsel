package io.github.hazymorning.amsel.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import io.github.hazymorning.amsel.ui.theme.ThemeMode

/**
 * The widget's page: the launcher opens it as the widget is placed, and again to change it. Leaving
 * without Done places nothing, or leaves the widget as it was.
 */
class WidgetSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, answer(widgetId))
        val manager = AppWidgetManager.getInstance(this)
        // Exported for launchers that open it themselves: only Amsel's own widgets are set here.
        if (manager.getAppWidgetInfo(widgetId)?.provider != ComponentName(this, AmselWidget::class.java)) {
            finish()
            return
        }
        val graph = graph
        // The new widget shows what plays at once, the picture on this page too.
        graph.widgets.follow()
        val size = sizeOf(manager.getAppWidgetOptions(widgetId))
        setContent {
            val prefs by graph.data.prefs.prefs.collectAsStateWithLifecycle()
            AmselTheme(ThemeMode.of(prefs?.theme)) {
                WidgetSetupRoute(
                    widgetId,
                    size,
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

    /** The widget's size on the home screen in portrait, as the launcher says it; 2 × 2 cells before it does. */
    private fun sizeOf(options: Bundle): DpSize {
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (width <= 0 || height <= 0) return DpSize(TWO_CELLS, TWO_CELLS)
        return DpSize(width.dp, height.dp)
    }

    private companion object {
        val TWO_CELLS = 180.dp
    }
}
