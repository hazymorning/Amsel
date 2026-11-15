package io.github.hazymorning.amsel.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkKeysTest {
    @Test fun aCoverIsTheSameAtHomeAndAway() {
        assertEquals(
            artworkKey("http://192.168.1.20:8095/imageproxy/ab12?size=256"),
            artworkKey("https://music.example.org/imageproxy/ab12?size=256"),
        )
    }

    @Test fun anotherSizeIsAnotherCover() {
        assertEquals("imageproxy/ab12?size=512", artworkKey("http://host:8095/imageproxy/ab12?size=512"))
    }

    @Test fun aFileOnThePhoneKeepsItsName() {
        assertEquals("file:///data/cover.jpg", artworkKey("file:///data/cover.jpg"))
    }
}
