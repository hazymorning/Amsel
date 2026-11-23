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
 * hears; nothing on the server can wake the phone, so the widgets look by themselves (`WidgetPlan`).
 */
class WidgetLooks : JobService() {
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val graph = graph
        running[params.jobId] = graph.scope.launch {
            try {
                graph.widgets.look()
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
interface WidgetLookPlan {
    /** Every quarter of an hour from now on, while there are widgets. */
    fun keep()

    /** Once more in [ms], in place of one planned before; null takes it back. */
    fun next(ms: Long?)

    /** No more looks: the last widget left the home screen. */
    fun stop()

    companion object {
        val None = object : WidgetLookPlan {
            override fun keep() = Unit

            override fun next(ms: Long?) = Unit

            override fun stop() = Unit
        }
    }
}

/**
 * The looks as Android's jobs: every 15 minutes, its shortest period, and once more where a widget's
 * speaker plays. Both wait for a network and leave a low battery alone; while the phone sleeps
 * Android holds them back, and one that is due runs as soon as it wakes, so the widgets are right
 * when the screen comes on. Not kept over a restart, which would take a permission: the widgets'
 * own update after it plans them again.
 */
class WidgetPlan(private val context: Context) : WidgetLookPlan {
    private val jobs: JobScheduler? get() = context.getSystemService(JobScheduler::class.java)

    override fun keep() {
        val jobs = jobs ?: return
        if (jobs.getPendingJob(EVERY) != null) return
        jobs.schedule(job(EVERY).setPeriodic(PERIOD_MS, FLEX_MS).build())
    }

    override fun next(ms: Long?) {
        val jobs = jobs ?: return
        if (ms == null) jobs.cancel(ONCE) else jobs.schedule(job(ONCE).setMinimumLatency(ms).build())
    }

    override fun stop() {
        jobs?.cancel(EVERY)
        jobs?.cancel(ONCE)
    }

    private fun job(id: Int) = JobInfo.Builder(id, ComponentName(context, WidgetLooks::class.java))
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setRequiresBatteryNotLow(true)

    private companion object {
        // Amsel's only jobs.
        const val EVERY = 1
        const val ONCE = 2
        const val PERIOD_MS = 15 * 60_000L
        const val FLEX_MS = 5 * 60_000L
    }
}

/**
 * How long until a widget's speaker shows something new, at [nowMs] on the clock the looks' times
 * stand on: its song ends; a book or an episode plays on for long, a station's title changes now
 * and then. Null while none plays: the quarter-hourly look is enough.
 */
internal fun nextLookIn(looks: Collection<WidgetLook>, nowMs: Long): Long? =
    looks.filter { it.playing }.minOfOrNull { look ->
        val start = look.clockMs
        when {
            look.spoken -> SPOKEN_LOOK_MS
            start != null && look.durationMs > 0 -> start + look.durationMs + SONG_CHANGE_MS - nowMs
            else -> STREAM_LOOK_MS
        }.coerceIn(SOONEST_LOOK_MS, LATEST_LOOK_MS)
    }

/** The server moves on to the next song a moment after the last one ends. */
private const val SONG_CHANGE_MS = 5_000L
private const val STREAM_LOOK_MS = 3 * 60_000L
private const val SPOKEN_LOOK_MS = 10 * 60_000L
private const val SOONEST_LOOK_MS = 30_000L
private const val LATEST_LOOK_MS = 10 * 60_000L
