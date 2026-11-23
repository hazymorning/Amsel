package io.github.hazymorning.amsel.widget

import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** When the widgets look at the speakers again while Amsel's socket is closed, and how Android is asked to. */
@RunWith(RobolectricTestRunner::class)
class WidgetLooksTest {
    private val now = 1_000_000L

    /** A song that started [playedMs] ago, as a widget's clock stands. */
    private fun song(playedMs: Long, lengthMs: Long = 200_000) =
        WidgetSample.music.copy(restMs = null, clockMs = now - playedMs, durationMs = lengthMs)

    @Test fun nothingPlayingLeavesItToTheQuarterHour() {
        assertNull(nextLookIn(listOf(WidgetSample.idle, WidgetSample.book), now))
        assertNull(nextLookIn(emptyList(), now))
    }

    @Test fun aSongIsLookedAtAgainAsItEnds() {
        // 50 s left, and a moment for the server to move on.
        assertEquals(55_000L, nextLookIn(listOf(song(playedMs = 150_000)), now))
    }

    @Test fun theSoonestSongCounts() {
        val looks = listOf(song(playedMs = 10_000), song(playedMs = 140_000), WidgetSample.idle)
        assertEquals(65_000L, nextLookIn(looks, now))
    }

    @Test fun aSongAtItsEndIsNotAskedAboutEverySecond() {
        assertEquals(30_000L, nextLookIn(listOf(song(playedMs = 199_000)), now))
        assertEquals(30_000L, nextLookIn(listOf(song(playedMs = 400_000)), now))
    }

    @Test fun aLongPieceIsLookedAtEveryTenMinutesAtMost() {
        assertEquals(600_000L, nextLookIn(listOf(song(playedMs = 0, lengthMs = 3_600_000)), now))
        assertEquals(600_000L, nextLookIn(listOf(WidgetSample.book.copy(playing = true)), now))
    }

    @Test fun aStationIsLookedAtForItsNextTitle() {
        val station = WidgetSample.music.copy(restMs = null, clockMs = null, durationMs = 0)
        assertEquals(180_000L, nextLookIn(listOf(station), now))
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val jobs = context.getSystemService(JobScheduler::class.java)

    @Test fun aQuarterHourlyLookIsAskedForOnce() {
        val plan = WidgetPlan(context)
        plan.keep()
        val first = jobs.allPendingJobs.single()
        plan.keep()
        assertEquals(listOf(first), jobs.allPendingJobs)
        assertTrue(first.isPeriodic)
        assertEquals(15 * 60_000L, first.intervalMillis)
        assertNotNull(first.requiredNetwork)
        assertTrue(first.isRequireBatteryNotLow)
    }

    @Test fun theNextLookTakesThePlaceOfOnePlannedBefore() {
        val plan = WidgetPlan(context)
        plan.keep()
        plan.next(120_000)
        plan.next(55_000)
        val once = jobs.allPendingJobs.filterNot { it.isPeriodic }
        assertEquals(listOf(55_000L), once.map { it.minLatencyMillis })
        plan.next(null)
        assertEquals(listOf(true), jobs.allPendingJobs.map { it.isPeriodic })
        plan.stop()
        assertTrue(jobs.allPendingJobs.isEmpty())
    }
}
