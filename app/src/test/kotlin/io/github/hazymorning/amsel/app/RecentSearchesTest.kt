package io.github.hazymorning.amsel.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecentSearchesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun <T> stored(block: suspend (RecentSearches) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            withTimeout(5_000) { block(RecentSearches(app, scope)) }
        } finally {
            scope.cancel()
        }
    }

    @Test fun aPlaylistTheServerFillsAnewIsStillOneWhenItComesBack() {
        val mix = MediaUi("library://playlist/7", MediaKind.Playlist, "Infinite Mix (library)", dynamic = true)
        val plain = MediaUi("library://playlist/9", MediaKind.Playlist, "Sunday Morning")
        stored { recent ->
            recent.add(plain)
            recent.items.first { it.size == 1 }
            recent.add(mix)
            recent.items.first { it.size == 2 }
        }
        // Read again from the phone, as after the app was closed.
        val kept = stored { recent -> recent.items.first { it.size == 2 } }
        assertEquals(listOf(RecentItem.Other(mix), RecentItem.Other(plain)), kept)
    }

    @Test fun anArtistsFourCoversAndASmartPlaylistsCropComeBackAsTheyWere() {
        val covers = listOf("c1", "c2", "c3", "c4").map { ArtRef("http://ma.local:8095/imageproxy/$it") }
        val artist = MediaUi(
            "library://artist/3",
            MediaKind.Artist,
            "Aurora Tone",
            art = covers[0].copy(quarters = covers),
        )
        val smart = MediaUi(
            "library://playlist/2",
            MediaKind.Playlist,
            "Random Album",
            art = ArtRef("http://ma.local:8095/imageproxy/grid", mosaic = true),
        )
        stored { recent ->
            recent.add(artist)
            recent.items.first { it.size == 1 }
            recent.add(smart)
            recent.items.first { it.size == 2 }
        }
        val kept = stored { recent -> recent.items.first { it.size == 2 } }
        assertEquals(listOf(RecentItem.Other(smart), RecentItem.Other(artist)), kept)
    }
}
