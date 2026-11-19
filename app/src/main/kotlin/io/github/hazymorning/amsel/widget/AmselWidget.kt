package io.github.hazymorning.amsel.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import io.github.hazymorning.amsel.graph
import kotlinx.coroutines.launch

/**
 * A widget as Android knows it. Amsel draws it itself whenever what it shows changes; Android asks
 * here after a restart, an update of Amsel, a launcher that lost its views, or a new size.
 */
open class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        work(context) { redraw(ids) }
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

/** Now playing: two cells by two as it comes. Its name stays from the first widget, so placed ones stay. */
class AmselWidget : WidgetProvider()

/** One button: one cell, the size of an app's icon. */
class AmselButtonWidget : WidgetProvider()

/** Buttons: two cells in one row. */
class AmselButtonsWidget : WidgetProvider()
