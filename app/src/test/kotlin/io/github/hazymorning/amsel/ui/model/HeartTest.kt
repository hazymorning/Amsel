package io.github.hazymorning.amsel.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartTest {
    private val song = NowPlayingUi(
        title = "Dawn Chorus",
        artist = "Blackbird",
        speaker = SpeakerUi(id = "kitchen", name = "Kitchen"),
        key = "library://track/10",
    )

    @Test fun `a song shows whether it is liked`() {
        assertEquals(false, song.heart(canWrite = true))
        assertEquals(true, song.copy(favorite = true).heart(canWrite = true))
    }

    @Test fun `a guest, a book, an episode or something not named yet gets no heart`() {
        assertNull(song.heart(canWrite = false))
        assertNull(song.copy(kind = MediaKind.Audiobook).heart(canWrite = true))
        assertNull(song.copy(kind = MediaKind.Episode).heart(canWrite = true))
        assertNull(song.copy(key = null).heart(canWrite = true))
    }
}
