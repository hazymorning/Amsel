package io.github.hazymorning.amsel.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews

/** Generated picker previews (Android 15); the system takes only a few setWidgetPreview calls an hour. */
internal class WidgetPreviews(
    private val context: Context,
    private val draw: suspend (WidgetKind, WidgetContent) -> RemoteViews,
) {
    private var offeredAt: Long? = null

    suspend fun offer(look: WidgetContent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        if (!look.signedIn || look.title == null) return
        val now = SystemClock.elapsedRealtime()
        if (offeredAt?.let { now - it < EVERY_MS } == true) return
        offeredAt = now
        val manager = AppWidgetManager.getInstance(context)
        for ((provider, kind) in Providers) {
            val views = draw(kind, look.atRest())
            try {
                manager.setWidgetPreview(
                    ComponentName(context, provider),
                    AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                    views,
                )
            } catch (e: RuntimeException) {
                // Refused by the system or the launcher: the picker keeps the static preview layout.
            }
        }
    }

    private companion object {
        /** Within the system's rate limit for generated previews. */
        const val EVERY_MS = 60 * 60 * 1000L
    }
}
