package io.github.hazymorning.amsel.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a tap on a speaker in Play on does, by what the current speaker and the tapped one do. */
class SpeakerChoiceTest {
    private fun tap(fromPhone: Boolean, holds: Boolean, plays: Boolean, targetPlays: Boolean, same: Boolean = false) =
        choiceOf(same, fromPhone, holds, plays, targetPlays)

    @Test fun thePhonesMusicGoesAlongPlayingOrPaused() {
        assertEquals(Choice.Move, tap(fromPhone = true, holds = true, plays = true, targetPlays = false))
        assertEquals(Choice.Move, tap(fromPhone = true, holds = true, plays = false, targetPlays = false))
    }

    @Test fun aSpeakerThatPlaysIsNeverTakenOverUnasked() {
        assertEquals(Choice.Ask, tap(fromPhone = true, holds = true, plays = true, targetPlays = true))
        // Paused music does not stop what plays there: the person sees that instead.
        assertEquals(Choice.Select, tap(fromPhone = true, holds = true, plays = false, targetPlays = true))
    }

    @Test fun musicPlayingOnASpeakerStaysUnlessThePersonTakesItAlong() {
        assertEquals(Choice.Ask, tap(fromPhone = false, holds = true, plays = true, targetPlays = false))
        assertEquals(Choice.Ask, tap(fromPhone = false, holds = true, plays = true, targetPlays = true))
    }

    @Test fun whatRestsOnASpeakerStaysThere() {
        assertEquals(Choice.Select, tap(fromPhone = false, holds = true, plays = false, targetPlays = false))
        assertEquals(Choice.Select, tap(fromPhone = false, holds = true, plays = false, targetPlays = true))
    }

    @Test fun withNothingToBringTheSpeakerOnlyChanges() {
        assertEquals(Choice.Select, tap(fromPhone = true, holds = false, plays = false, targetPlays = false))
        assertEquals(Choice.Select, tap(fromPhone = false, holds = false, plays = false, targetPlays = true))
    }

    @Test fun theSpeakerInUseStays() {
        assertEquals(Choice.Stay, tap(fromPhone = true, holds = true, plays = true, targetPlays = true, same = true))
    }
}
