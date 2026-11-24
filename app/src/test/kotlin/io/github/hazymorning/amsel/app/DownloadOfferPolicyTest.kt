package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.data.downloads.DownloadState
import io.github.hazymorning.amsel.sample.PlayerSample
import io.github.hazymorning.amsel.sample.SampleData
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadOfferPolicyTest {
    private val phone = SampleData.speakers[0]
    private val onPhone = PlayerSample.book.copy(speaker = phone)
    private val uri = checkNotNull(onPhone.key)

    private val playing = MutableStateFlow<NowPlayingUi?>(null)
    private val inFront = MutableStateFlow(true)
    private val downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val asked = mutableSetOf<String>()
    private var server: (String) -> MediaUi? = { book(it) }
    private val offered = mutableListOf<String>()

    private fun book(uri: String, downloadable: Boolean = true) =
        MediaUi(uri, MediaKind.Audiobook, "Moby-Dick", downloadable = downloadable)

    private fun TestScope.listen() {
        val offer = DownloadOfferPolicy(playing, inFront, downloads, { it in asked }, { asked += it }, { server(it) })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { offer.offers.collect { offered += it.key } }
    }

    @Test fun aBookThatPlaysOnThisPhoneIsOfferedOnce() = runTest {
        listen()
        playing.value = onPhone
        playing.value = onPhone.copy(isPlaying = false)
        playing.value = onPhone
        assertEquals(listOf(uri), offered)
        assertEquals(setOf(uri), asked)
    }

    @Test fun notOnASpeakerNorWhilePaused() = runTest {
        listen()
        playing.value = PlayerSample.book
        playing.value = onPhone.copy(isPlaying = false)
        assertEquals(emptyList<String>(), offered)
    }

    @Test fun musicIsNeverOffered() = runTest {
        listen()
        playing.value = PlayerSample.nowPlaying.copy(speaker = phone)
        assertEquals(emptyList<String>(), offered)
    }

    @Test fun itWaitsUntilTheAppIsInFront() = runTest {
        inFront.value = false
        listen()
        playing.value = onPhone
        assertEquals(emptyList<String>(), offered)
        inFront.value = true
        assertEquals(listOf(uri), offered)
    }

    @Test fun whatIsOnThePhoneOrOnItsWayIsNotOffered() = runTest {
        downloads.value = mapOf(uri to DownloadState.Waiting())
        listen()
        playing.value = onPhone
        assertEquals(emptyList<String>(), offered)
        assertEquals(emptySet<String>(), asked)
    }

    @Test fun whatWasOfferedBeforeIsNotOfferedAgain() = runTest {
        asked += uri
        listen()
        playing.value = onPhone
        assertEquals(emptyList<String>(), offered)
    }

    @Test fun whatDoesNotComeFromAudiobookshelfIsLeftAlone() = runTest {
        server = { book(it, downloadable = false) }
        listen()
        playing.value = onPhone
        assertEquals(emptyList<String>(), offered)
        assertEquals(setOf(uri), asked)
    }

    @Test fun whileTheServerCannotSayItIsAskedAgainLater() = runTest {
        server = { null }
        listen()
        playing.value = onPhone
        assertEquals(emptySet<String>(), asked)
        server = { book(it) }
        playing.value = onPhone.copy(isPlaying = false)
        playing.value = onPhone
        assertEquals(listOf(uri), offered)
    }
}
