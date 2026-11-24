package io.github.hazymorning.amsel.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayOnActionTest {
    private fun tap(fromPhone: Boolean, holds: Boolean, plays: Boolean, targetPlays: Boolean, same: Boolean = false) =
        choiceOf(same, fromPhone, holds, plays, targetPlays)

    @Test fun thePhonesMusicGoesAlongPlayingOrPaused() {
        assertEquals(PlayOnAction.Move, tap(fromPhone = true, holds = true, plays = true, targetPlays = false))
        assertEquals(PlayOnAction.Move, tap(fromPhone = true, holds = true, plays = false, targetPlays = false))
    }

    @Test fun aSpeakerThatPlaysIsNeverTakenOverUnasked() {
        assertEquals(PlayOnAction.Ask, tap(fromPhone = true, holds = true, plays = true, targetPlays = true))
        // Paused music does not stop what plays there: the user sees that instead.
        assertEquals(PlayOnAction.Select, tap(fromPhone = true, holds = true, plays = false, targetPlays = true))
    }

    @Test fun musicPlayingOnASpeakerStaysUnlessThePersonTakesItAlong() {
        assertEquals(PlayOnAction.Ask, tap(fromPhone = false, holds = true, plays = true, targetPlays = false))
        assertEquals(PlayOnAction.Ask, tap(fromPhone = false, holds = true, plays = true, targetPlays = true))
    }

    @Test fun whatRestsOnASpeakerStaysThere() {
        assertEquals(PlayOnAction.Select, tap(fromPhone = false, holds = true, plays = false, targetPlays = false))
        assertEquals(PlayOnAction.Select, tap(fromPhone = false, holds = true, plays = false, targetPlays = true))
    }

    @Test fun withNothingToBringTheSpeakerOnlyChanges() {
        assertEquals(PlayOnAction.Select, tap(fromPhone = true, holds = false, plays = false, targetPlays = false))
        assertEquals(PlayOnAction.Select, tap(fromPhone = false, holds = false, plays = false, targetPlays = true))
    }

    @Test fun theSpeakerInUseStays() {
        assertEquals(
            PlayOnAction.Stay,
            tap(fromPhone = true, holds = true, plays = true, targetPlays = true, same = true),
        )
    }
}
