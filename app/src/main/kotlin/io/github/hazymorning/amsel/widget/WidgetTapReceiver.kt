package io.github.hazymorning.amsel.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.playback.PhoneNotReadyException
import io.github.hazymorning.amsel.playback.failureText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A tap on one of a widget's buttons. The launcher sends it with background starts allowed, which
 * lets Amsel come to the front for ten seconds (docs/notes/widget.md): long enough for the playback
 * service to show its notification when the tap starts sound.
 */
class WidgetTapReceiver : BroadcastReceiver() {
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
            val intent = Intent(context, WidgetTapReceiver::class.java)
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

/** What a widget's button does on its speaker. */
internal class WidgetTapHandler(
    private val context: Context,
    private val states: StateButtonHandler,
    /** The book or episode heard last, from the server. */
    private val lastStarted: suspend () -> WidgetItem?,
) {
    /**
     * [button] was tapped while its widget showed [shownPlaying] and [shown]; it acts through
     * [hands], and [say] puts a sentence on the widget where it went wrong.
     */
    suspend fun tap(
        button: WidgetSlot,
        hands: WidgetTransport,
        shownPlaying: Boolean,
        shown: suspend () -> WidgetContent?,
        say: (String) -> Unit,
    ) {
        hands.wake()
        try {
            when (button.button) {
                WidgetButton.PlayPause -> if (shownPlaying) hands.pause() else hands.play()

                WidgetButton.Next -> hands.next()

                WidgetButton.Previous -> hands.previous()

                WidgetButton.Quieter, WidgetButton.Louder, WidgetButton.Shuffle, WidgetButton.Repeat,
                WidgetButton.Favourite,
                -> (shown()?.let { states.tap(button.button, it) } as? StateButtonHandler.Refusal.AtLimit)
                    ?.let { say(limitText(it.limit)) }

                WidgetButton.ContinueListening -> lastStarted()?.let { hands.play(it) }
                    ?: say(context.getString(R.string.widget_nothing_to_continue))

                WidgetButton.PlayItem -> button.item?.let { hands.play(it) }

                // Open Amsel itself (WidgetCellsViews.press).
                WidgetButton.Speakers, WidgetButton.UpNext, WidgetButton.Group -> Unit
            }
        } catch (e: MaException) {
            say(context.getString(failureText(e, play = true)))
        } catch (e: PhoneNotReadyException) {
            say(context.getString(failureText(e, play = true)))
        }
    }

    private fun limitText(limit: Int): String =
        context.getString(R.string.widget_at_limit, context.getString(R.string.player_volume_percent, limit))
}
