package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.hazymorning.amsel.app.Tip
import io.github.hazymorning.amsel.data.home.SectionLayout
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.sample.HomeSample
import io.github.hazymorning.amsel.testing.ScreenshotTest
import io.github.hazymorning.amsel.testing.ShotVariant
import io.github.hazymorning.amsel.testing.ShotVariant.Dark
import io.github.hazymorning.amsel.testing.ShotVariant.LargeFont
import io.github.hazymorning.amsel.testing.ShotVariant.Light
import io.github.hazymorning.amsel.ui.model.SpokenWord
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import org.junit.Test

class HomeShots : ScreenshotTest() {
    @Test fun home() = snap("home", ShotVariant.entries) { Home(HomeSample.home) }

    @Test fun grid() = snap("home_grid", ShotVariant.entries) { Home(HomeSample.grid) }

    /** Search's field at Home's top, over the sections. */
    @Test fun search() = snap("home_search", ShotVariant.entries) {
        val search = SectionUi("s9", SectionOption.Search, SectionLayout.Row, emptyList(), titled = false)
        Home(HomeSample.home.copy(sections = listOf(search) + HomeSample.home.sections))
    }

    /**
     * Continue listening with its title hidden, under Search's field: a group's gap under the
     * field, not a heading's room, then the next section's heading as ever.
     */
    @Test fun untitled() = snap("home_untitled", ShotVariant.entries) {
        val search = SectionUi("s9", SectionOption.Search, SectionLayout.Row, emptyList(), titled = false)
        val listening = HomeSample.listening.sections.map { it.copy(titled = false) }
        Home(HomeSample.home.copy(sections = listOf(search) + listening + HomeSample.home.sections))
    }

    @Test fun layouts() = snap("home_layouts", ShotVariant.entries) { Home(HomeSample.layouts) }

    @Test fun tip() = snap("home_tip", ShotVariant.entries) { Home(HomeSample.home, Tip.More) }

    @Test fun editing() = snap("home_edit", listOf(Light, LargeFont)) { Home(HomeSample.editing) }

    /** In German the sentence has other words, and they set where its chips stand. */
    @Test fun editingInGerman() = snap("home_edit_german", qualifiers = "de") { Home(HomeSample.editing) }

    /**
     * The longest names on a narrow phone: the longest choice still stands on its chip's one line,
     * and at 200 % the title takes a second line, the chip's value wraps and the bar says "Bearbeiten".
     */
    @Test fun editingLongNamesInGerman() = snap("home_edit_narrow_german", listOf(Light, LargeFont), "de-w360dp") {
        Home(longNames)
    }

    /** What to show, at its top: every line a choice, the groups parted by space. */
    @Test fun whatSheet() = snapScreen("home_what", ShotVariant.entries) {
        OverEditing { What(SectionOption.Everything) }
    }

    @Test fun whatSheetInGerman() = snapScreen("home_what_german", qualifiers = "de") {
        OverEditing { What(SectionOption.Everything) }
    }

    /** The last choice is the chosen one: the sheet opens with it in view, clear of its foot. */
    @Test fun whatSheetAtItsEnd() = snapScreen("home_what_end") { OverEditing { What(SectionOption.YourPodcasts) } }

    @Test fun howSheet() = snapScreen("home_how") { OverEditing { HowSheetContent(SectionLayout.Row) {} } }

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

    @Test fun offline() = snap("home_offline") { Home(HomeSample.offline) }

    @Test fun listening() = snap("home_listening", listOf(Light, Dark, LargeFont)) { Home(HomeSample.listening) }

    @Test fun playedHere() = snap("home_played_here", listOf(Light, Dark)) { Home(HomeSample.playedHere) }

    @Test fun playedHereInGerman() = snap("home_played_here_german", qualifiers = "de") { Home(HomeSample.playedHere) }
}

@Composable
private fun Home(state: HomeUiState, tip: Tip? = null) =
    HomeScreen(state, onOpen = {}, onMore = {}, onSeeAll = {}, onOpenWebUi = {}, tip = tip)

/** A sheet of edit mode in its own window over it, as a chip or ⋯ opens it there. */
@Composable
private fun OverEditing(sheet: @Composable () -> Unit) {
    Home(HomeSample.editing)
    AmselSheet(onDismiss = {}) { sheet() }
}

/** What to show for a library that holds books and podcasts too: every choice there is. */
@Composable
private fun What(current: SectionOption) =
    WhatSheetContent(current, SpokenWord(books = true, podcasts = true, newEpisodes = true)) {}

/** A name a household gives a section, and the longest choices and layouts. */
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
