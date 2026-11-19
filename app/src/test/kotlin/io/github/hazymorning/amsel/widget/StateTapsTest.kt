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
class StateTapsTest {
    private class Asked(var limit: Int = 0) : WidgetActions {
        val volumes = mutableListOf<Pair<String, Int>>()
        val phone = mutableListOf<Boolean>()
        val asked = mutableListOf<String>()

        override fun volumeLimit() = limit

        override suspend fun volume(speakerId: String, level: Int) {
            volumes += speakerId to level
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
    private val taps = StateTaps(asked) { now }
    private val kitchen = WidgetLook(
        speakerId = "kitchen",
        speaker = "Kitchen",
        volume = 40,
        title = "Lanterns",
        key = "library://track/1",
        favourite = false,
        shuffle = false,
        repeat = RepeatMode.Off,
    )

    @Test fun louderSetsTheShownSpeakerAHeardStepHigher() = runTest {
        taps.tap(WidgetButton.Louder, kitchen)
        assertEquals(listOf("kitchen" to 45), asked.volumes)
        assertTrue(asked.phone.isEmpty())
    }

    @Test fun tapsInARowStepOnBeforeTheSpeakerReports() = runTest {
        taps.tap(WidgetButton.Louder, kitchen)
        now += 300
        taps.tap(WidgetButton.Louder, kitchen)
        now += 300
        taps.tap(WidgetButton.Quieter, kitchen)
        assertEquals(listOf(45, 50, 45), asked.volumes.map { it.second })
    }

    @Test fun aLaterTapStepsFromWhatTheSpeakerShows() = runTest {
        taps.tap(WidgetButton.Louder, kitchen)
        now += 5_000
        taps.tap(WidgetButton.Louder, kitchen.copy(volume = 60))
        assertEquals(listOf(45, 65), asked.volumes.map { it.second })
    }

    @Test fun louderStopsAtTheLimitAndSaysSo() = runTest {
        asked.limit = 70
        assertEquals(null, taps.tap(WidgetButton.Louder, kitchen.copy(volume = 68)))
        now += 300
        assertEquals(StateTaps.Refusal.AtLimit(70), taps.tap(WidgetButton.Louder, kitchen.copy(volume = 68)))
        assertEquals(listOf(70), asked.volumes.map { it.second })
    }

    @Test fun louderNeverLowersALevelAboveTheLimit() = runTest {
        asked.limit = 70
        assertEquals(StateTaps.Refusal.AtLimit(70), taps.tap(WidgetButton.Louder, kitchen.copy(volume = 80)))
        assertTrue(asked.volumes.isEmpty())
        taps.tap(WidgetButton.Quieter, kitchen.copy(volume = 80))
        assertEquals(listOf(75), asked.volumes.map { it.second })
    }

    @Test fun nearSilenceTheStepsAreFine() {
        assertEquals(6, volumeAfter(8, up = false, limit = 0))
        assertEquals(0, volumeAfter(1, up = false, limit = 0))
        assertEquals(10, volumeAfter(8, up = true, limit = 0))
        assertEquals(100, volumeAfter(98, up = true, limit = 0))
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
