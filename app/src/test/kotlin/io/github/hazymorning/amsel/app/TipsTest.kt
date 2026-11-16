package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.data.Prefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which tip shows: one at a time, each once, the next on another day. */
@OptIn(ExperimentalCoroutinesApi::class)
class TipsTest {
    /** Settings out of the bar, so the pull down that opens them has its tip. */
    private val prefs = MutableStateFlow<Prefs?>(Prefs(barPlaces = listOf("home", "search", "-settings")))
    private val inFront = MutableStateFlow(true)
    private var day = 20_000L

    private fun TestScope.tips() = Tips(
        prefs,
        inFront,
        save = { id, restDay ->
            prefs.update { it?.copy(tipsSeen = it.tipsSeen + id, tipRestDay = restDay ?: it.tipRestDay) }
        },
        scope = backgroundScope + UnconfinedTestDispatcher(testScheduler),
        today = { day },
    )

    private fun reopen() {
        inFront.value = false
        inFront.value = true
    }

    @Test fun theFirstTipShowsUntilItIsPutAway() = runTest {
        val tips = tips()
        assertEquals(Tip.EditHome, tips.current.value)
        reopen()
        assertEquals(Tip.EditHome, tips.current.value)
        tips.seen(Tip.EditHome)
        assertNull(tips.current.value)
    }

    @Test fun theNextComesAnotherDayAndEachOnlyOnce() = runTest {
        val tips = tips()
        tips.seen(Tip.EditHome)
        reopen()
        assertNull(tips.current.value)
        day++
        reopen()
        assertEquals(Tip.Settings, tips.current.value)
        tips.seen(Tip.Settings)
        day++
        reopen()
        assertEquals(Tip.More, tips.current.value)
        tips.seen(Tip.More)
        day++
        reopen()
        assertNull(tips.current.value)
    }

    @Test fun settingsInTheBarNeedNoTipAboutThePull() = runTest {
        prefs.value = Prefs()
        val tips = tips()
        tips.seen(Tip.EditHome)
        day++
        reopen()
        assertEquals(Tip.More, tips.current.value)
    }

    @Test fun whatThePersonFoundUntoldIsNeverTold() = runTest {
        val tips = tips()
        tips.seen(Tip.Settings)
        assertEquals(Tip.EditHome, tips.current.value)
        tips.seen(Tip.EditHome)
        day++
        reopen()
        assertEquals(Tip.More, tips.current.value)
    }

    @Test fun nothingShowsBeforeWhatWasSeenIsRead() = runTest {
        prefs.value = null
        val tips = tips()
        assertNull(tips.current.value)
        prefs.value = Prefs(tipsSeen = setOf("edit_home", "settings", "more"))
        assertNull(tips.current.value)
    }

    @Test fun whatWasSeenIsStoredOnce() = runTest {
        var saves = 0
        val tips =
            Tips(prefs, inFront, { _, _ -> saves++ }, backgroundScope + UnconfinedTestDispatcher(testScheduler)) { day }
        tips.seen(Tip.More)
        tips.seen(Tip.More)
        assertEquals(1, saves)
    }
}
