package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.playback.PhoneNotReadyException
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The order of the steps when music moves to and from This phone. */
class SpeakerStepsTest {
    private val phone = SpeakerUi("phone-1", "This phone", isPhone = true)
    private val kitchen = SpeakerUi("kitchen", "Kitchen")
    private val office = SpeakerUi("office", "Office")

    /** Records each step; "keep{" and "}" mark the span This phone is kept on the server. */
    private class Recorder(private val phoneJoins: String? = "phone-1", private val transferFails: Boolean = false) :
        SpeakerSteps {
        val steps = mutableListOf<String>()

        override suspend fun select(target: SpeakerUi) {
            steps += "select ${target.id}"
        }

        override suspend fun keepPhoneDuring(block: suspend () -> Unit) {
            steps += "keep{"
            block()
            steps += "}"
        }

        override suspend fun awaitPhone(): String {
            steps += "await phone"
            return phoneJoins ?: throw PhoneNotReadyException()
        }

        override fun releasePhoneHold() {
            steps += "release hold"
        }

        override suspend fun transfer(from: String, to: String, play: Boolean) {
            steps += "transfer $from>$to" + if (play) "" else " resting"
            if (transferFails) throw MaCommandException("player_queues/transfer", 10, "gone")
        }
    }

    @Test fun movingAwayFromThePhoneKeepsItOnTheServerUntilTheTransferIsDone() = runBlocking {
        val r = Recorder()
        moveMusic(from = phone, to = kitchen, r)
        // Selecting Kitchen stops the phone's Sendspin, and with it the queue the transfer reads.
        assertEquals(listOf("keep{", "select kitchen", "transfer phone-1>kitchen", "}"), r.steps)
    }

    @Test fun movingToThePhoneWaitsForItAndReleasesItsHold() = runBlocking {
        val r = Recorder()
        moveMusic(from = kitchen, to = phone, r)
        assertEquals(listOf("select phone-1", "await phone", "release hold", "transfer kitchen>phone-1"), r.steps)
    }

    @Test fun pausedMusicMovesWithoutPlaying() = runBlocking {
        val away = Recorder()
        moveMusic(from = phone, to = kitchen, away, play = false)
        assertEquals(listOf("keep{", "select kitchen", "transfer phone-1>kitchen resting", "}"), away.steps)
        // The phone's hold stays: nothing is to sound until Play is tapped.
        val home = Recorder()
        moveMusic(from = kitchen, to = phone, home, play = false)
        assertEquals(listOf("select phone-1", "await phone", "transfer kitchen>phone-1 resting"), home.steps)
    }

    @Test fun movingBetweenSpeakersIsOneTransfer() = runBlocking {
        val r = Recorder()
        moveMusic(from = kitchen, to = office, r)
        assertEquals(listOf("select office", "transfer kitchen>office"), r.steps)
    }

    @Test fun aPhoneThatDoesNotJoinOrAFailedTransferSaysWhy() = runBlocking {
        val noPhone = Recorder(phoneJoins = null)
        val notJoined = runCatching { moveMusic(from = kitchen, to = phone, noPhone) }.exceptionOrNull()
        assertTrue(notJoined is PhoneNotReadyException)
        assertEquals(listOf("select phone-1", "await phone"), noPhone.steps)
        val refused = runCatching { moveMusic(from = kitchen, to = office, Recorder(transferFails = true)) }
        assertTrue(refused.exceptionOrNull() is MaCommandException)
    }
}
