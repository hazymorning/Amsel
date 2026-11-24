package io.github.hazymorning.amsel.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import io.github.hazymorning.amsel.R
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The widgets on a home screen, drawn by the kind they were placed as and the size they stand at. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetControllerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = AppWidgetManager.getInstance(context)
    private val home = shadowOf(manager)
    private val store = WidgetStore(Files.createTempDirectory("widget").toFile())
    private var next = 1

    private val source = object : WidgetSource {
        override val content = MutableStateFlow(WidgetSample.music.copy(art = null, artLarge = null))

        override fun contentOf(id: String) = flowOf(WidgetSample.idle)

        override val failures = emptyFlow<Pair<String, String>>()
    }

    /** Widgets working in a scope of the test's own, which ends with it. */
    private fun TestScope.widgets(
        connected: () -> Boolean = { false },
        refreshSpeakers: suspend () -> Boolean = { true },
        plan: WidgetRefreshScheduler = WidgetRefreshScheduler.None,
    ): WidgetController {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        ended += scope
        return widgets(scope, connected = connected, refreshSpeakers = refreshSpeakers, plan = plan)
    }

    private val ended = mutableListOf<CoroutineScope>()

    @After fun end() = ended.forEach { it.cancel() }

    private fun widgets(
        scope: CoroutineScope,
        live: Boolean = false,
        connected: () -> Boolean = { false },
        refreshSpeakers: suspend () -> Boolean = { true },
        plan: WidgetRefreshScheduler = WidgetRefreshScheduler.None,
    ) = WidgetController(
        context,
        store,
        ImageLoader(context),
        scope,
        hands = { error("No taps here") },
        lastStarted = { null },
        source = { source },
        live = live,
        connected = connected,
        refreshSpeakers = refreshSpeakers,
        plan = plan,
        elapsedRealtime = { NOW },
    )

    /** The looks asked of Android. */
    private class Plans : WidgetRefreshScheduler {
        var periodic = 0
        var cancelled = 0
        var once = 0

        override fun schedulePeriodic() {
            periodic++
        }

        override fun scheduleOnce() {
            once++
        }

        override fun cancelAll() {
            cancelled++
        }
    }

    /** A song 150 s into its 200, as the speaker reports it. */
    private val playingSong =
        WidgetSample.music.copy(
            art = null,
            artLarge = null,
            restMs = null,
            clockMs = NOW - 150_000,
            durationMs = 200_000,
        )

    /** A widget of [provider] placed at [width] by [height] dp. */
    private fun place(provider: Class<*>, width: Float, height: Float): Int {
        val id = next++
        val name = ComponentName(context, provider)
        home.addBoundWidget(id, AppWidgetProviderInfo().apply { this.provider = name })
        resize(id, width, height)
        return id
    }

    private fun resize(id: Int, width: Float, height: Float) {
        val options = Bundle().apply {
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(width, height)))
        }
        manager.updateAppWidgetOptions(id, options)
    }

    /** Runs what is due until [done]: the store reads and writes on a thread of its own. */
    private fun TestScope.settle(done: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            testScheduler.advanceUntilIdle()
            if (done()) return
            Thread.sleep(SETTLE_STEP_MS)
        }
    }

    /** As [settle], with the test's time passing slowly, so the store's thread keeps up with a timeout. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.settleSlowly(done: () -> Boolean) {
        repeat(SETTLE_TRIES) {
            testScheduler.advanceTimeBy(SLOW_STEP_MS)
            testScheduler.runCurrent()
            if (done()) return
            Thread.sleep(SETTLE_STEP_MS)
        }
    }

    private fun text(id: Int, view: Int): String? = home.getViewFor(id)?.findViewById<TextView>(view)?.text?.toString()

    private fun shown(id: Int, view: Int): Boolean =
        home.getViewFor(id)?.findViewById<View>(view)?.visibility == View.VISIBLE

    @Test fun eachKindComesWithItsButtons() = runTest {
        val button = place(AmselButtonWidget::class.java, 80f, 96f)
        val buttons = place(AmselButtonsWidget::class.java, 172f, 96f)
        val nowPlaying = place(AmselWidget::class.java, 172f, 204f)
        widgets().redraw(intArrayOf(button, buttons, nowPlaying))
        assertEquals("Play", home.getViewFor(button).contentDescription)
        assertEquals(3, home.getViewFor(buttons).cells().size)
        assertEquals(false, shown(buttons, R.id.widget_words))
        assertEquals(true, shown(nowPlaying, R.id.widget_words))
        assertEquals(3, home.getViewFor(nowPlaying).cells().size)
    }

    @Test fun aWidgetTakesTheFormOfItsNewSize() = runTest {
        val widgets = widgets()
        val id = place(AmselButtonsWidget::class.java, 172f, 96f)
        widgets.redraw(intArrayOf(id))
        resize(id, 356f, 96f)
        widgets.redraw(intArrayOf(id))
        assertNotNull(home.getViewFor(id).findViewById(R.id.widget_art_box))
    }

    @Test fun aWidgetShowsWhatPlaysOnceAmselFollows() = runTest {
        val widgets = widgets()
        val id = place(AmselWidget::class.java, 172f, 204f)
        widgets.follow()
        settle { text(id, R.id.widget_title) == "Lanterns" }
        assertEquals("Lanterns", text(id, R.id.widget_title))
    }

    /** Placed while Amsel follows the music, a widget shows what plays without its page. */
    @Test fun aWidgetPlacedWhileAmselRunsFollowsAtOnce() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        ended += scope
        val widgets = widgets(scope, live = true)
        val id = place(AmselButtonsWidget::class.java, 172f, 96f)
        widgets.redraw(intArrayOf(id))
        resize(id, 172f, 204f)
        widgets.redraw(intArrayOf(id))
        settle { text(id, R.id.widget_title) == "Lanterns" }
        assertEquals("Lanterns", text(id, R.id.widget_title))
    }

    @Test fun aWidgetOfItsOwnSpeakerShowsThatSpeaker() = runTest {
        val widgets = widgets()
        val id = place(AmselWidget::class.java, 172f, 204f)
        widgets.setUp(id, WidgetSetup(speaker = "bedroom"))
        settle { text(id, R.id.widget_speaker) == "Bedroom" }
        assertEquals("Bedroom", text(id, R.id.widget_speaker))
        assertEquals(WidgetSetup(speaker = "bedroom"), store.setup(id))
    }

    /** Amsel's socket closed, a look reads the speakers and draws them; the next is the half-hourly one. */
    @Test fun aLookWhileTheSocketIsClosedDrawsWhatPlays() = runTest {
        source.content.value = playingSong
        var glances = 0
        val plans = Plans()
        val widgets = widgets(refreshSpeakers = { glances++ == 0 }, plan = plans)
        val id = place(AmselWidget::class.java, 172f, 204f)
        val looking = launch { widgets.refresh() }
        settleSlowly { looking.isCompleted }
        assertEquals(1, glances)
        assertEquals("Lanterns", text(id, R.id.widget_title))
        assertEquals(1, plans.periodic)
        assertEquals(0, plans.once)
    }

    @Test fun whileTheSocketIsConnectedTheWidgetsFollowItWithoutLooking() = runTest {
        var glances = 0
        val plans = Plans()
        val widgets = widgets(connected = { true }, refreshSpeakers = { glances++ == 0 }, plan = plans)
        place(AmselWidget::class.java, 172f, 204f)
        val looking = launch { widgets.refresh() }
        settleSlowly { looking.isCompleted }
        assertEquals(0, glances)
        assertEquals(0, plans.once)
    }

    @Test fun withoutWidgetsNothingIsLookedAt() = runTest {
        var glances = 0
        val plans = Plans()
        val widgets = widgets(refreshSpeakers = { glances++ == 0 }, plan = plans)
        widgets.refresh()
        assertEquals(0, glances)
        assertEquals(1, plans.cancelled)
    }

    private companion object {
        const val SETTLE_TRIES = 1000
        const val SETTLE_STEP_MS = 10L
        const val SLOW_STEP_MS = 50L
        const val NOW = 5_000_000L
    }
}
