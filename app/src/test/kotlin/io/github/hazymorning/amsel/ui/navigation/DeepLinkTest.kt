package io.github.hazymorning.amsel.ui.navigation

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeepLinkTest {
    private fun of(link: String) = DeepLink.of(Uri.parse(link))

    @Test fun eachLinkOpensItsPlace() {
        assertEquals(DeepLink.Search, of("amsel://search"))
        assertEquals(DeepLink.NowPlaying, of("amsel://now-playing"))
        assertEquals(DeepLink.Speakers, of("amsel://speakers"))
        assertEquals(DeepLink.UpNext, of("amsel://up-next"))
        assertEquals(DeepLink.Group, of("amsel://group"))
        // As a widget may write them.
        assertEquals(DeepLink.Search, of("AMSEL://Search/"))
    }

    @Test fun anythingElseOpensNothing() {
        assertNull(of("amsel://settings"))
        assertNull(of("https://search"))
        assertNull(of("amsel:search"))
        assertNull(DeepLink.of(null))
    }
}
