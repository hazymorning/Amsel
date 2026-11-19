package io.github.hazymorning.amsel.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.github.hazymorning.amsel.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A tap on one of a widget's buttons. The launcher sends it with background starts allowed, which
 * lets Amsel come to the front for ten seconds (docs/notes/widget.md): long enough for the playback
 * service to show its notification when the tap starts sound.
 */
class WidgetTaps : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TAP) return
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val slot = intent.getIntExtra(SLOT, -1)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || slot !in 0 until MAX_BUTTONS) return
        val shownPlaying = intent.getBooleanExtra(SHOWN_PLAYING, false)
        val pending = goAsync()
        val graph = context.graph
        graph.scope.launch(Dispatchers.Main) {
            try {
                withTimeoutOrNull(BUDGET_MS) { graph.widgets.tap(widgetId, slot, shownPlaying) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAP = "io.github.hazymorning.amsel.widget.TAP"
        private const val SLOT = "slot"
        private const val SHOWN_PLAYING = "shown_playing"

        /** Within the ten seconds a receiver has. */
        private const val BUDGET_MS = 9_000L

        /** Button [slot] of widget [widgetId], as it shows now: play or pause. */
        fun pending(context: Context, widgetId: Int, slot: Int, shownPlaying: Boolean): PendingIntent {
            val intent = Intent(context, WidgetTaps::class.java)
                .setAction(TAP)
                // Extras alone do not tell two pending intents apart.
                .setData("amsel-widget://$widgetId/$slot".toUri())
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .putExtra(SLOT, slot)
                .putExtra(SHOWN_PLAYING, shownPlaying)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
