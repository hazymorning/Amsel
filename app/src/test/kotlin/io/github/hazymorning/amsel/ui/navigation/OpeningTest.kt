package io.github.hazymorning.amsel.ui.navigation

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpeningTest {
    private fun of(link: String) = Opening.of(Uri.parse(link))

    @Test fun eachLinkOpensItsPlace() {
        assertEquals(Opening.Search, of("amsel://search"))
        assertEquals(Opening.NowPlaying, of("amsel://now-playing"))
        assertEquals(Opening.Speakers, of("amsel://speakers"))
        // As a widget may write them.
        assertEquals(Opening.Search, of("AMSEL://Search/"))
    }

    @Test fun anythingElseOpensNothing() {
        assertNull(of("amsel://settings"))
        assertNull(of("https://search"))
        assertNull(of("amsel:search"))
        assertNull(Opening.of(null))
    }
}
