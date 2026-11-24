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

/** Refreshes the widgets while the socket is closed: events come only over an open socket (docs/API-NOTES.md, 9). */
class WidgetRefreshJobService : JobService() {
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val graph = graph
        running[params.jobId] = graph.scope.launch {
            try {
                graph.widgets.refresh()
            } finally {
                // onStopJob already removed a stopped job; jobFinished must not follow it.
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

interface WidgetRefreshScheduler {
    fun schedulePeriodic()

    fun scheduleOnce()

    fun cancelAll()

    companion object {
        val None = object : WidgetRefreshScheduler {
            override fun schedulePeriodic() = Unit

            override fun scheduleOnce() = Unit

            override fun cancelAll() = Unit
        }
    }
}

/** Not persisted, which needs RECEIVE_BOOT_COMPLETED: after a reboot the providers' onUpdate schedules again. */
class WidgetRefreshJobScheduler(private val context: Context) : WidgetRefreshScheduler {
    private val jobs: JobScheduler? get() = context.getSystemService(JobScheduler::class.java)

    override fun schedulePeriodic() {
        val jobs = jobs ?: return
        // Rescheduling would restart the period; a job from an earlier version with a shorter one is replaced.
        if (jobs.getPendingJob(EVERY)?.intervalMillis == PERIOD_MS) return
        jobs.schedule(job(EVERY).setPeriodic(PERIOD_MS, FLEX_MS).build())
    }

    override fun scheduleOnce() {
        jobs?.schedule(job(ONCE).build())
    }

    override fun cancelAll() {
        jobs?.cancel(EVERY)
        jobs?.cancel(ONCE)
    }

    private fun job(id: Int) = JobInfo.Builder(id, ComponentName(context, WidgetRefreshJobService::class.java))
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setRequiresBatteryNotLow(true)

    private companion object {
        // Job ids are app-wide; these are Amsel's only jobs.
        const val EVERY = 1
        const val ONCE = 2
        const val PERIOD_MS = 30 * 60_000L
        const val FLEX_MS = 5 * 60_000L
    }
}
