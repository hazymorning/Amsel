package io.github.hazymorning.amsel.ui.screens.home

import io.github.hazymorning.amsel.data.home.SectionLayout
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.data.home.SectionSource
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateTest {
    private fun album(n: Int) = MediaUi("library://album/$n", MediaKind.Album, "Album $n")

    private fun section(id: String, items: List<MediaUi> = emptyList(), loading: Boolean = false) =
        SectionUi(id, SectionOption.NewAlbums, SectionLayout.Row, items, loading = loading)

    @Test fun onlySectionsWithSomethingShowOutsideEditMode() {
        val state = HomeUiState(listOf(section("a", listOf(album(1))), section("b"), section("c", loading = true)))
        assertEquals(listOf("a", "c"), state.visible.map { it.id })
        assertEquals(listOf("a", "b", "c"), state.copy(editing = true).visible.map { it.id })
    }

    @Test fun loadingMeansEverySectionOnScreenStillLoads() {
        assertTrue(HomeUiState(listOf(section("a", loading = true), section("b"))).loading)
        assertFalse(HomeUiState(listOf(section("a", loading = true), section("b", listOf(album(1))))).loading)
        assertFalse(HomeUiState().loading)
    }

    @Test fun anEmptyLibraryHasNoContent() {
        assertFalse(HomeUiState().hasContent)
        assertFalse(HomeUiState(listOf(section("a", loading = true))).hasContent)
        assertTrue(HomeUiState(listOf(section("a"), section("b", listOf(album(1))))).hasContent)
    }

    @Test fun theSentenceGivesEachChipTheWordBeforeIt() {
        assertEquals(Sentence("Show", "as"), Sentence.of("Show %1\$s as %2\$s"))
        assertEquals(Sentence("Zeigt", "als"), Sentence.of("Zeigt %1\$s als %2\$s"))
        assertEquals(Sentence("", "als"), Sentence.of("%1\$s als %2\$s"))
    }

    @Test fun aSentenceWithoutBothChipsStillShowsItsWords() {
        assertEquals(Sentence("", "Show"), Sentence.of("Show"))
        assertEquals(Sentence("", "%2\$s then %1\$s"), Sentence.of("%2\$s then %1\$s"))
    }

    @Test fun aChoiceIsNamedDifferentlyOnlyWhereItsTitleDoesNotSayItsKind() {
        // Played here's title is Recently played's; Home names the speaker after it.
        assertEquals(
            listOf(SectionOption.PlayedHere, SectionOption.NewAlbums, SectionOption.AllStations),
            SectionOption.entries.filter { it.choice != it.title },
        )
    }

    @Test fun everyOptionStandsOnceInTheWhatSheet() {
        val listed = WhatGroups.flatten()
        assertEquals(SectionOption.entries.toSet(), listed.toSet())
        assertEquals(SectionOption.entries.size, listed.size)
    }

    @Test fun aGroupOfTheWhatSheetHoldsOneKindOrTheSpokenWord() {
        val spoken = setOf(SectionSource.Audiobooks, SectionSource.Podcasts)
        WhatGroups.drop(1).forEach { group ->
            val sources = group.map { it.source }.toSet()
            assertTrue("$group mixes $sources", sources.size == 1 || sources == spoken)
        }
    }
}
