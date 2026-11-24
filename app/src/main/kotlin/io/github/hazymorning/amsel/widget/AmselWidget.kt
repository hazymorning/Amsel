package io.github.hazymorning.amsel.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import io.github.hazymorning.amsel.graph
import kotlinx.coroutines.launch

/** [WidgetController] pushes redraws; Android calls here after a reboot, an app update, a launcher reset, a resize. */
open class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        work(context) {
            redraw(ids)
            refreshSoon()
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        work(context) { redraw(intArrayOf(id)) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        work(context) { forget(ids) }
    }

    override fun onDisabled(context: Context) {
        context.graph.widgets.removed()
    }

    private fun work(context: Context, block: suspend WidgetController.() -> Unit) {
        val pending = goAsync()
        val graph = context.graph
        graph.scope.launch {
            try {
                graph.widgets.block()
            } finally {
                pending.finish()
            }
        }
    }
}

/** Now playing, under the first kind's name: launchers bind placed widgets to the provider's class name. */
class AmselWidget : WidgetProvider()

class AmselButtonWidget : WidgetProvider()

class AmselButtonsWidget : WidgetProvider()

internal val Providers: Map<Class<*>, WidgetKind> = mapOf(
    AmselButtonWidget::class.java to WidgetKind.Button,
    AmselButtonsWidget::class.java to WidgetKind.Buttons,
    AmselWidget::class.java to WidgetKind.NowPlaying,
)
