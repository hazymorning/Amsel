package io.github.hazymorning.amsel.widget

import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.sample.PlayerSample
import io.github.hazymorning.amsel.sample.SampleData
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetContentTest {
    private val ready = SessionState.Ready(ServerInfo("s1", "2.11.0", 99), user = null)
    private val kitchen = SampleData.speakers[1]

    @Test fun showsWhatPlaysWhereWhileConnected() {
        val look = widgetContent(kitchen, "Kitchen", SampleData.nowPlaying, ready)
        assertEquals("Kitchen", look.speaker)
        assertEquals("Lanterns", look.title)
        assertEquals("Marlene Vogt", look.subtitle)
        assertTrue(look.playing)
        assertEquals(40, look.volume)
        assertEquals(SampleData.nowPlaying.art?.sized(COVER_PX), look.art)
    }

    @Test fun restsAsLastHeardOnceTheServerIsOutOfReach() {
        val look =
            widgetContent(kitchen, "Kitchen", SampleData.nowPlaying, SessionState.Waiting("Home"), current = false)
        assertFalse(look.playing)
        assertNull(look.clockMs)
        assertEquals("Lanterns", look.title)
        assertTrue(look.signedIn)
    }

    @Test fun playsOnWhileWhatTheServerSaidHolds() {
        val look =
            widgetContent(kitchen, "Kitchen", SampleData.nowPlaying, SessionState.Waiting("Home"), current = true)
        assertTrue(look.playing)
    }

    @Test fun signedOutSaysSo() {
        assertFalse(widgetContent(kitchen, "Kitchen", null, SessionState.NeedsSetup).signedIn)
        assertFalse(widgetContent(kitchen, "Kitchen", null, SessionState.Unsupported).signedIn)
        assertTrue(widgetContent(kitchen, "Kitchen", null, SessionState.Restoring).signedIn)
    }

    @Test fun aGroupIsNamedAsTheDockNamesIt() {
        val group = kitchen.copy(others = 2)
        assertEquals("Kitchen +2", widgetContent(group, "Kitchen", null, ready).speaker)
    }

    @Test fun thisPhoneHasNoLevelOfItsOwn() {
        val phone = SpeakerUi("phone", "", isPhone = true, volume = 30)
        val look = widgetContent(phone, "This phone", SampleData.nowPlaying.copy(speaker = phone), ready)
        assertNull(look.volume)
        assertTrue(look.phone)
    }

    @Test fun aBookIsSpoken() {
        val look = widgetContent(kitchen, "Kitchen", PlayerSample.book, ready)
        assertTrue(look.spoken)
        assertEquals("Herman Melville", look.subtitle)
    }

    @Test fun nothingPlayingShowsNoTitle() {
        val look = widgetContent(kitchen, "Kitchen", null, ready)
        assertNull(look.title)
        assertNull(look.art)
        assertFalse(look.playing)
    }

    @Test fun aPlayStartingWithoutItsTitleShowsNone() {
        val starting = SampleData.nowPlaying.copy(title = "", artist = "", album = null)
        val look = widgetContent(kitchen, "Kitchen", starting, ready)
        assertNull(look.title)
        assertNull(look.subtitle)
    }

    @Test fun withoutAnArtistTheAlbumStandsUnder() {
        val look = widgetContent(kitchen, "Kitchen", SampleData.nowPlaying.copy(artist = ""), ready)
        assertEquals("Blue Hour", look.subtitle)
    }
}
