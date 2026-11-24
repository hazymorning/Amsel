package io.github.hazymorning.amsel.widget

import io.github.hazymorning.amsel.api.RepeatMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A widget's volume, shuffle, repeat and heart act on the speaker it shows, from what it shows: on a
 * cold start the tap that started Amsel came before Amsel knew its speaker, and went to the phone.
 */
class StateButtonHandlerTest {
    private class Asked(var limit: Int = 0, var step: Int? = null) : WidgetActions {
        /** Each volume asked, as the widget shows it: a set level, or the server's step and where it lands. */
        val volumes = mutableListOf<Pair<String, Int>>()
        val nudges = mutableListOf<String>()
        val phone = mutableListOf<Boolean>()
        val asked = mutableListOf<String>()

        override fun volumeLimit() = limit

        override fun volumeStep(level: Int) = step ?: serverStep(level)

        override suspend fun nudge(speakerId: String, up: Boolean, shown: Int) {
            volumes += speakerId to shown
            nudges += if (up) "up" else "down"
        }

        override suspend fun volume(speakerId: String, level: Int) {
            volumes += speakerId to level
            nudges += "set"
        }

        override fun phoneVolume(up: Boolean) {
            phone += up
        }

        override suspend fun shuffle(speakerId: String, on: Boolean) {
            asked += "shuffle $speakerId $on"
        }

        override suspend fun repeat(speakerId: String, mode: RepeatMode) {
            asked += "repeat $speakerId $mode"
        }

        override fun favourite(uri: String, on: Boolean) {
            asked += "heart $uri $on"
        }
    }

    private var now = 10_000L
    private val asked = Asked()
    private val taps = StateButtonHandler(asked) { now }
    private val kitchen = WidgetContent(
        speakerId = "kitchen",
        speaker = "Kitchen",
        volume = 40,
        title = "Lanterns",
        key = "library://track/1",
        favourite = false,
        shuffle = false,
        repeat = RepeatMode.Off,
    )

    /** The server takes the step its settings name; the widget shows where it lands. */
    @Test fun louderTakesTheServersStepOnTheShownSpeaker() = runTest {
        asked.step = 1
        taps.tap(WidgetButton.Louder, kitchen)
        assertEquals(listOf("kitchen" to 41), asked.volumes)
        assertEquals(listOf("up"), asked.nudges)
        assertTrue(asked.phone.isEmpty())
    }

    @Test fun withoutASettingTheServersLadderShows() = runTest {
        taps.tap(WidgetButton.Louder, kitchen)
        now += 3_000
        taps.tap(WidgetButton.Quieter, kitchen.copy(volume = 8))
        now += 3_000
        taps.tap(WidgetButton.Louder, kitchen.copy(volume = 20))
        assertEquals(listOf(43, 7, 22), asked.volumes.map { it.second })
    }

    @Test fun tapsInARowStepOnBeforeTheSpeakerReports() = runTest {
        asked.step = 1
        taps.tap(WidgetButton.Louder, kitchen)
        now += 300
        taps.tap(WidgetButton.Louder, kitchen)
        now += 300
        taps.tap(WidgetButton.Quieter, kitchen)
        assertEquals(listOf(41, 42, 41), asked.volumes.map { it.second })
        assertEquals(listOf("up", "up", "down"), asked.nudges)
    }

    @Test fun aLaterTapStepsFromWhatTheSpeakerShows() = runTest {
        asked.step = 1
        taps.tap(WidgetButton.Louder, kitchen)
        now += 5_000
        taps.tap(WidgetButton.Louder, kitchen.copy(volume = 60))
        assertEquals(listOf(41, 61), asked.volumes.map { it.second })
    }

    /** A step that would pass the limit sets the limit; from the limit Louder does nothing and says so. */
    @Test fun louderStopsAtTheLimitAndSaysSo() = runTest {
        asked.limit = 70
        asked.step = 5
        assertEquals(null, taps.tap(WidgetButton.Louder, kitchen.copy(volume = 68)))
        now += 300
        assertEquals(StateButtonHandler.Refusal.AtLimit(70), taps.tap(WidgetButton.Louder, kitchen.copy(volume = 68)))
        assertEquals(listOf(70), asked.volumes.map { it.second })
        assertEquals(listOf("set"), asked.nudges)
    }

    @Test fun louderNeverLowersALevelAboveTheLimit() = runTest {
        asked.limit = 70
        assertEquals(StateButtonHandler.Refusal.AtLimit(70), taps.tap(WidgetButton.Louder, kitchen.copy(volume = 80)))
        assertTrue(asked.volumes.isEmpty())
        taps.tap(WidgetButton.Quieter, kitchen.copy(volume = 80))
        assertEquals(listOf(78), asked.volumes.map { it.second })
    }

    @Test fun theLadderIsTheServers() {
        assertEquals(listOf(1, 2, 3, 3, 2, 1), listOf(5, 20, 30, 70, 80, 95).map(::serverStep))
    }

    @Test fun thisPhoneMovesThePhonesOwnVolume() = runTest {
        taps.tap(WidgetButton.Quieter, kitchen.copy(phone = true, volume = null))
        assertEquals(listOf(false), asked.phone)
        assertTrue(asked.volumes.isEmpty())
    }

    @Test fun shuffleRepeatAndTheHeartTurnWhatTheWidgetShows() = runTest {
        taps.tap(WidgetButton.Shuffle, kitchen)
        taps.tap(WidgetButton.Repeat, kitchen.copy(repeat = RepeatMode.All))
        taps.tap(WidgetButton.Favourite, kitchen)
        assertEquals(
            listOf("shuffle kitchen true", "repeat kitchen One", "heart library://track/1 true"),
            asked.asked,
        )
    }

    @Test fun aBookHasNoShuffleNoRepeatNoHeart() = runTest {
        val book = kitchen.copy(spoken = true, shuffle = null, repeat = null, favourite = null)
        for (button in listOf(WidgetButton.Shuffle, WidgetButton.Repeat, WidgetButton.Favourite)) taps.tap(button, book)
        assertTrue(asked.asked.isEmpty())
    }
}
