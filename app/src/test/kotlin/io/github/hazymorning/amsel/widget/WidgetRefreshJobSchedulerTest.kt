package io.github.hazymorning.amsel.widget

import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** How Android is asked to look at the speakers for the widgets while Amsel's socket is closed. */
@RunWith(RobolectricTestRunner::class)
class WidgetRefreshJobSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val jobs = context.getSystemService(JobScheduler::class.java)

    @Test fun aHalfHourlyLookIsAskedForOnce() {
        val plan = WidgetRefreshJobScheduler(context)
        plan.schedulePeriodic()
        val first = jobs.allPendingJobs.single()
        plan.schedulePeriodic()
        assertEquals(listOf(first), jobs.allPendingJobs)
        assertTrue(first.isPeriodic)
        assertEquals(30 * 60_000L, first.intervalMillis)
        assertNotNull(first.requiredNetwork)
        assertTrue(first.isRequireBatteryNotLow)
    }

    @Test fun aLookOnceRunsBesideTheHalfHourlyOne() {
        val plan = WidgetRefreshJobScheduler(context)
        plan.schedulePeriodic()
        plan.scheduleOnce()
        plan.scheduleOnce()
        val oneOff = jobs.allPendingJobs.filterNot { it.isPeriodic }
        assertEquals(listOf(0L), oneOff.map { it.minLatencyMillis })
        plan.cancelAll()
        assertTrue(jobs.allPendingJobs.isEmpty())
    }
}
