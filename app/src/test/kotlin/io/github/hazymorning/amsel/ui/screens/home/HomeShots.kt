package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.hazymorning.amsel.app.Tip
import io.github.hazymorning.amsel.data.home.SectionLayout
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.sample.HomeSample
import io.github.hazymorning.amsel.testing.ScreenshotTest
import io.github.hazymorning.amsel.testing.ShotVariant.Dark
import io.github.hazymorning.amsel.testing.ShotVariant.LargeFont
import io.github.hazymorning.amsel.testing.ShotVariant.Light
import io.github.hazymorning.amsel.ui.model.SpokenWord
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import org.junit.Test

class HomeShots : ScreenshotTest() {
    // At 200 % the cards drop to one column.
    @Test fun grid() = snap("home_grid", listOf(Light, LargeFont)) { Home(HomeSample.grid) }

    @Test fun asItComes() = snap("home_as_it_comes") { Home(HomeSample.asItComes) }

    // Untitled sections stand 32 dp apart, not a heading's height; at night the cards take their
    // covers' hue.
    @Test fun untitled() = snap("home_untitled", listOf(Dark)) {
        val search = SectionUi("s9", SectionOption.Search, SectionLayout.Row, emptyList(), titled = false)
        val listening = HomeSample.listening.sections.map { it.copy(titled = false) }
        Home(HomeSample.home.copy(sections = listOf(search) + listening + HomeSample.home.sections))
    }

    @Test fun tip() = snap("home_tip") { Home(HomeSample.home, Tip.More) }

    // The longest choice fits its chip on one line; at 200 % the title takes a second line, the chip's
    // value wraps and the bar shortens to "Bearbeiten".
    @Test fun editingLongNamesInGerman() = snap("home_edit_narrow_german", listOf(Light, LargeFont), "de-w360dp") {
        Home(longNames)
    }

    @Test fun whatSheet() = snapScreen("home_what") { OverEditing { What(SectionOption.Everything) } }

    @Test fun sectionMore() = snapScreen("home_edit_more") {
        OverEditing {
            SectionActionsSheetContent(
                stringResource(PlaybackR.string.recently_played),
                titled = true,
                onTitled = {},
                onRemove = {},
            )
        }
    }
}

@Composable
private fun Home(state: HomeUiState, tip: Tip? = null) =
    HomeScreen(state, onOpen = {}, onMore = {}, onSeeAll = {}, onOpenWebUi = {}, tip = tip)

@Composable
private fun OverEditing(sheet: @Composable () -> Unit) {
    Home(HomeSample.editing)
    AmselSheet(onDismiss = {}) { sheet() }
}

// Books and podcasts too, so every choice is listed.
@Composable
private fun What(current: SectionOption) =
    WhatSheetContent(current, SpokenWord(books = true, podcasts = true, newEpisodes = true)) {}

// A long custom name, and the longest choices and layouts.
private val longNames = HomeSample.editing.sections.let { sections ->
    HomeUiState(
        listOf(
            sections[1].copy(
                option = SectionOption.RecentPlaylists,
                layout = SectionLayout.Small,
                title = "Musik für lange Sonntagnachmittage im Garten",
            ),
            sections[2].copy(option = SectionOption.RecentStations, layout = SectionLayout.Big),
            sections[3].copy(option = SectionOption.TopArtists, layout = SectionLayout.Small),
        ),
        editing = true,
    )
}
