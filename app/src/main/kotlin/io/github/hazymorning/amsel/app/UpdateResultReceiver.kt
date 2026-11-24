package io.github.hazymorning.amsel.app

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import io.github.hazymorning.amsel.graph

/** Android's answer to an update session: its confirmation to show, or that it did not install. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val version = intent.getStringExtra(VERSION) ?: return
        val app = context.applicationContext
        val updates = context.graph.updates
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                updates.confirm {
                    try {
                        app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        true
                    } catch (e: ActivityNotFoundException) {
                        false
                    }
                }
            }

            // Success replaces Amsel and ends this run; an abort is the person's No in Android's dialog.
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> Unit

            else -> updates.notInstalled(version)
        }
    }

    companion object {
        private const val VERSION = "version"

        fun pending(context: Context, session: Int, version: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            session,
            Intent(context, UpdateResultReceiver::class.java).putExtra(VERSION, version),
            // Mutable: Android adds the status and its confirmation to it.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}
