package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.data.HINTS_ALL
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfferControllerTest {
    private val offers = OfferController()
    private val taken = mutableListOf<String>()

    private fun offer(about: String) = Offer(about, title = about, button = 0, secondTapTakes = true, take = {
        taken +=
            about
    })

    @Test fun anOfferShowsUntilTakenAndRunsOnce() {
        val station = offer("radio/1")
        offers.offer(station)
        assertSame(station, offers.shown.value)

        offers.take(station)
        offers.take(station)
        assertNull(offers.shown.value)
        assertEquals(listOf("radio/1"), taken)
    }

    @Test fun aSecondTapOnTheSameThingTakesIt() {
        offers.offer(offer("radio/1"))
        offers.offer(offer("radio/1"))
        assertNull(offers.shown.value)
        assertEquals(listOf("radio/1"), taken)
    }

    @Test fun aHintAskedForAgainShowsAnewAndIsNeverTakenUnasked() {
        val first =
            Offer("headphones", title = "Blue Hour", button = 0, hint = "headphones", take = { taken += "first" })
        val again =
            Offer("headphones", title = "Blue Hour", button = 0, hint = "headphones", take = { taken += "again" })
        offers.offer(first)
        offers.offer(again)
        assertSame(again, offers.shown.value)
        assertEquals(emptyList<String>(), taken)
    }

    @Test fun aHintPassedUpThreeTimesInARowIsNotOfferedAgainAndOneTakenStartsOver() {
        val passes = mutableMapOf<String, Int>()
        val hints = OfferController(passes = { passes }, setPasses = { kind, n -> passes[kind] = n })
        fun hint() = Offer("headphones", title = "Blue Hour", button = 0, hint = "headphones", take = {
            taken += "here"
        })

        repeat(2) { hints.dismiss(hint().also(hints::offer)) }
        hints.offer(hint())
        hints.take(checkNotNull(hints.shown.value))
        assertEquals(0, passes["headphones"])

        repeat(3) { hints.dismiss(hint().also(hints::offer)) }
        hints.offer(hint())
        assertNull(hints.shown.value)
        assertEquals(listOf("here"), taken)
    }

    @Test fun aKindTurnedOffInSettingsIsNeverOfferedAndAllOffTurnsEveryOneOff() {
        val off = mutableSetOf("headphones")
        val hints = OfferController(off = { off })
        fun hint(kind: String) = Offer(kind, title = kind, button = 0, hint = kind, take = {})
        assertEquals(false, hints.offer(hint("headphones")))
        assertEquals(true, hints.offer(hint("ended")))
        off += HINTS_ALL
        assertEquals(false, hints.offer(hint("sleep")))
        // What answers a tap is no hint, and always comes.
        assertEquals(true, hints.offer(offer("radio/1")))
    }

    @Test fun nothingIsOfferedWhileTheAppIsAwayAndWhatShowedGoesWhenItLeaves() = runTest {
        val inFront = MutableStateFlow(false)
        val away = OfferController(inFront, scope = backgroundScope)
        away.offer(offer("radio/1"))
        assertNull(away.shown.value)

        inFront.value = true
        away.offer(offer("radio/1"))
        assertEquals("radio/1", away.shown.value?.about)
        inFront.value = false
        runCurrent()
        assertNull(away.shown.value)
        assertEquals(emptyList<String>(), taken)
    }

    @Test fun anotherOfferTakesThePlaceOfTheLastAndADismissedOneDoesNothing() {
        val first = offer("radio/1")
        val second = offer("episode/2")
        offers.offer(first)
        offers.offer(second)
        assertSame(second, offers.shown.value)

        offers.dismiss(first)
        assertSame(second, offers.shown.value)
        offers.dismiss(second)
        assertNull(offers.shown.value)
        assertEquals(emptyList<String>(), taken)
    }
}
