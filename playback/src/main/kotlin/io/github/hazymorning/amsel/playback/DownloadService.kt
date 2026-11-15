package io.github.hazymorning.amsel.playback

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.hazymorning.amsel.data.downloads.DownloadState
import io.github.hazymorning.amsel.data.downloads.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the downloads in front of everything else ([Downloads.work]), with a notification of what
 * comes down and how far, and a button that stops them: a book is hundreds of megabytes, and Android
 * ends background work long before that. It stops itself when nothing is left.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val downloads = PlaybackGraph.instance.downloads
        if (downloads == null) {
            // Asked to start in front, it must show itself before it may stop.
            startForeground(notification(emptyMap()))
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == STOP) {
            downloads.states.value.filterValues { it !is DownloadState.Done }.keys.forEach(downloads::remove)
        }
        try {
            startForeground(notification(downloads.states.value))
        } catch (e: IllegalStateException) {
            // Android 15 refuses once the day's six hours are spent: what waits goes on tomorrow.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                e !is ForegroundServiceStartNotAllowedException
            ) {
                throw e
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (work?.isActive != true) {
            work = scope.launch {
                val shown = launch {
                    downloads.states.map(::notification).distinctUntilChanged { a, b -> a.same(b) }.collect(::show)
                }
                // Its files are written on IO, as everything Audiobookshelf answers is read there.
                withContext(Dispatchers.IO) { downloads.work() }
                shown.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 gives a data sync six hours a day; what is left goes on at the next start of the app. */
    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
    }

    private fun show(notification: Notification) =
        getSystemService(NotificationManager::class.java).notify(ID, notification)

    private fun notification(states: Map<String, DownloadState>): Notification {
        channel()
        val left = states.values.count { it is DownloadState.Waiting || it is DownloadState.Running }
        val running = states.values.filterIsInstance<DownloadState.Running>().firstOrNull()
        val waiting = states.values.filterIsInstance<DownloadState.Waiting>().firstOrNull()
        val text = when {
            running != null -> null
            waiting?.offline == true -> getString(R.string.download_waiting_network)
            waiting?.forWifi == true -> getString(R.string.download_waiting_wifi)
            else -> null
        }
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, DownloadService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
            .setContentTitle(resources.getQuantityString(R.plurals.download_title, left.coerceAtLeast(1), left))
            .setContentText(text)
            .setProgress(
                PROGRESS_MAX,
                ((running?.fraction ?: 0f) * PROGRESS_MAX).toInt(),
                running == null && text == null,
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(
                packageManager.getLaunchIntentForPackage(packageName)?.let {
                    PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
                },
            )
            .addAction(0, getString(R.string.download_stop), stop)
            .build()
    }

    private fun channel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val ID = 2
        private const val CHANNEL = "downloads"
        private const val STOP = "stop"
        private const val PROGRESS_MAX = 1000

        /**
         * Starts the service, which then runs every download in line. From the background Android
         * refuses that (12 on); the downloads then go on the next time the app is in front.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: IllegalStateException) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    e !is ForegroundServiceStartNotAllowedException
                ) {
                    throw e
                }
            }
        }
    }
}

/** Two notifications that look alike: redrawing one for every chunk would only flicker. */
private fun Notification.same(other: Notification): Boolean =
    extras.getCharSequence(Notification.EXTRA_TITLE) == other.extras.getCharSequence(Notification.EXTRA_TITLE) &&
        extras.getCharSequence(Notification.EXTRA_TEXT) == other.extras.getCharSequence(Notification.EXTRA_TEXT) &&
        extras.getInt(Notification.EXTRA_PROGRESS) / STEP == other.extras.getInt(Notification.EXTRA_PROGRESS) / STEP &&
        extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) ==
        other.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)

/** A step of the bar worth showing: a tenth of a percent would redraw the notification hundreds of times. */
private const val STEP = 10
