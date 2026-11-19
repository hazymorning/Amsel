package io.github.hazymorning.amsel.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import io.github.hazymorning.amsel.graph
import kotlinx.coroutines.launch

/**
 * The widget as Android knows it. Amsel draws it itself whenever what it shows changes; Android
 * asks here only after a restart, an update of Amsel, or a launcher that lost its views.
 */
class AmselWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        work(context) { redraw(ids) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        work(context) { forget(ids) }
    }

    override fun onDisabled(context: Context) {
        context.graph.widgets.removed()
    }

    private fun work(context: Context, block: suspend Widgets.() -> Unit) {
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
