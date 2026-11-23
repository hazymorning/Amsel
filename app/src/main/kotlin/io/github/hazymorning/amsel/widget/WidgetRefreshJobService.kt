package io.github.hazymorning.amsel.widget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.github.hazymorning.amsel.graph
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Looks at the speakers for the widgets while Amsel's own socket is closed: music started elsewhere
 * (Music Assistant itself, Home Assistant, another phone), a widget's own speaker, a speaker that
 * played on after Amsel let its socket go. While the socket is open the widgets follow it as it
 * hears; nothing on the server can wake the phone, so the widgets look by themselves (`WidgetRefreshJobScheduler`).
 */
class WidgetRefreshJobService : JobService() {
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val graph = graph
        running[params.jobId] = graph.scope.launch {
            try {
                graph.widgets.refresh()
            } finally {
                // A job Android stopped is not finished again.
                if (running.remove(params.jobId) != null) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return false
    }
}

/** When the widgets look at the speakers while Amsel's socket is closed. */
interface WidgetRefreshScheduler {
    /** Every half hour from now on, while there are widgets. */
    fun keep()

    /** Once, as soon as Android lets it run. */
    fun once()

    /** No more looks: the last widget left the home screen. */
    fun stop()

    companion object {
        val None = object : WidgetRefreshScheduler {
            override fun keep() = Unit

            override fun once() = Unit

            override fun stop() = Unit
        }
    }
}

/**
 * The looks as Android's jobs: every 30 minutes, and once when Android asks for the widgets anew.
 * Both wait for a network and leave a low battery alone; while the phone sleeps Android holds them
 * back, and one that is due runs as soon as it wakes. Not kept over a restart, which would take a
 * permission: the widgets' own update after it plans them again.
 */
class WidgetRefreshJobScheduler(private val context: Context) : WidgetRefreshScheduler {
    private val jobs: JobScheduler? get() = context.getSystemService(JobScheduler::class.java)

    override fun keep() {
        val jobs = jobs ?: return
        // A job planned by an earlier version keeps its shorter period until replaced.
        if (jobs.getPendingJob(EVERY)?.intervalMillis == PERIOD_MS) return
        jobs.schedule(job(EVERY).setPeriodic(PERIOD_MS, FLEX_MS).build())
    }

    override fun once() {
        jobs?.schedule(job(ONCE).build())
    }

    override fun stop() {
        jobs?.cancel(EVERY)
        jobs?.cancel(ONCE)
    }

    private fun job(id: Int) = JobInfo.Builder(id, ComponentName(context, WidgetRefreshJobService::class.java))
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setRequiresBatteryNotLow(true)

    private companion object {
        // Amsel's only jobs.
        const val EVERY = 1
        const val ONCE = 2
        const val PERIOD_MS = 30 * 60_000L
        const val FLEX_MS = 5 * 60_000L
    }
}
