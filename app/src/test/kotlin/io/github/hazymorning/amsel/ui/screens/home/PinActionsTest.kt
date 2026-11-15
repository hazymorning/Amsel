package io.github.hazymorning.amsel.ui.screens.home

import io.github.hazymorning.amsel.data.ServerExtras
import io.github.hazymorning.amsel.sample.HomeSample
import io.github.hazymorning.amsel.sample.SampleData
import io.github.hazymorning.amsel.ui.model.ItemAction
import io.github.hazymorning.amsel.ui.model.ItemActionsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pin to Home and Unpin from Home in the ⋮ sheet. */
class PinActionsTest {
    private val pinnable =
        listOf(SampleData.albums[0], SampleData.playlists[0], SampleData.artists[0], HomeSample.stations[0])
    private val pins = setOf(ItemAction.PinToHome, ItemAction.UnpinFromHome)
    private val extras = ServerExtras(mix = true, similarSongs = true)

    @Test fun albumsPlaylistsArtistsAndStationsCanBePinnedLast() = pinnable.forEach { item ->
        assertEquals(ItemAction.PinToHome, ItemActionsUiState.forMedia(item, pinned = false).actions.last())
        assertEquals(ItemAction.UnpinFromHome, ItemActionsUiState.forMedia(item, pinned = true).actions.last())
        assertEquals(ItemAction.PinToHome, ItemActionsUiState.forMedia(item, false, extras = extras).actions.last())
    }

    @Test fun onlyOneOfTheTwoIsOffered() = pinnable.forEach { item ->
        assertEquals(1, ItemActionsUiState.forMedia(item, pinned = false).actions.count { it in pins })
    }

    @Test fun withoutAHomeToPinToNeitherIsOffered() {
        assertTrue(ItemActionsUiState.forMedia(SampleData.albums[0]).actions.none { it in pins })
    }

    @Test fun songsAreNotPinned() {
        assertTrue(ItemActionsUiState.forTrack(SampleData.tracks[0]).actions.none { it in pins })
    }
}
